// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VFileProperty;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.OpenTHashSet;
import com.intellij.util.containers.Queue;
import com.intellij.util.text.FilePathHashingStrategy;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.intellij.openapi.util.Pair.pair;
import static com.intellij.util.containers.ContainerUtil.newTroveSet;

/**
 * @author max
 */
public class RefreshWorker {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vfs.newvfs.persistent.RefreshWorker");
  private static final Logger LOG_ATTRIBUTES = Logger.getInstance("#com.intellij.openapi.vfs.newvfs.persistent.RefreshWorker_Attributes");

  private final boolean myIsRecursive;
  private final Queue<Pair<NewVirtualFile, FileAttributes>> myRefreshQueue = new Queue<>(100);
  private final List<VFileEvent> myEvents = new ArrayList<>();
  private volatile boolean myCancelled;
  private final LocalFileSystemRefreshWorker myLocalFileSystemRefreshWorker;

  public RefreshWorker(@NotNull NewVirtualFile refreshRoot, boolean isRecursive) {
    boolean canUseNioRefresher = refreshRoot.isInLocalFileSystem() && !(refreshRoot.getFileSystem() instanceof TempFileSystem);
    myLocalFileSystemRefreshWorker = canUseNioRefresher && Registry.is("vfs.use.nio-based.local.refresh.worker") ?
                                     new LocalFileSystemRefreshWorker(refreshRoot, isRecursive) : null;
    myIsRecursive = isRecursive;
    myRefreshQueue.addLast(pair(refreshRoot, null));
  }

  @NotNull
  public List<VFileEvent> getEvents() {
    if (myLocalFileSystemRefreshWorker != null) return myLocalFileSystemRefreshWorker.getEvents();
    return myEvents;
  }

  public void cancel() {
    if (myLocalFileSystemRefreshWorker != null) myLocalFileSystemRefreshWorker.cancel();
    myCancelled = true;
  }

  public void scan() {
    if (myLocalFileSystemRefreshWorker != null) {
      myLocalFileSystemRefreshWorker.scan();
      return;
    }
    NewVirtualFile root = myRefreshQueue.peekFirst().first;
    NewVirtualFileSystem fs = root.getFileSystem();
    if (root.isDirectory()) {
      fs = PersistentFS.replaceWithNativeFS(fs);
    }
    try {
      processQueue(fs, PersistentFS.getInstance());
    }
    catch (RefreshCancelledException e) {
      LOG.trace("refresh cancelled");
    }
  }

  private void processQueue(NewVirtualFileSystem fs, PersistentFS persistence) throws RefreshCancelledException {
    TObjectHashingStrategy<String> strategy = FilePathHashingStrategy.create(fs.isCaseSensitive());

    while (!myRefreshQueue.isEmpty()) {
      Pair<NewVirtualFile, FileAttributes> pair = myRefreshQueue.pullFirst();
      NewVirtualFile file = pair.first;
      boolean fileDirty = file.isDirty();
      if (LOG.isTraceEnabled()) LOG.trace("file=" + file + " dirty=" + fileDirty);
      if (!fileDirty) continue;

      checkCancelled(file);

      FileAttributes attributes = pair.second != null ? pair.second : fs.getAttributes(file);
      if (attributes == null) {
        scheduleDeletion(file);
        file.markClean();
        continue;
      }

      NewVirtualFile parent = file.getParent();
      if (parent != null && checkAndScheduleFileTypeChange(parent, file, attributes)) {
        // ignore everything else
        file.markClean();
        continue ;
      }

      if (file.isDirectory()) {
        boolean fullSync = ((VirtualDirectoryImpl)file).allChildrenLoaded();
        if (fullSync) {
          fullDirRefresh(fs, persistence, strategy, (VirtualDirectoryImpl)file);
        }
        else {
          partialDirRefresh(fs, strategy, (VirtualDirectoryImpl)file);
        }
      }
      else {
        long currentTimestamp = persistence.getTimeStamp(file);
        long upToDateTimestamp = attributes.lastModified;
        long currentLength = persistence.getLastRecordedLength(file);
        long upToDateLength = attributes.length;

        if (currentTimestamp != upToDateTimestamp || currentLength != upToDateLength) {
          scheduleUpdateContent(file);
        }
      }

      boolean currentWritable = persistence.isWritable(file);
      boolean upToDateWritable = attributes.isWritable();
      if (LOG_ATTRIBUTES.isTraceEnabled()) {
        LOG_ATTRIBUTES.trace("file=" + file + " writable vfs=" + file.isWritable() + " persistence=" + currentWritable + " real=" + upToDateWritable);
      }
      if (currentWritable != upToDateWritable) {
        scheduleAttributeChange(file, VirtualFile.PROP_WRITABLE, currentWritable, upToDateWritable);
      }

      if (SystemInfo.isWindows) {
        boolean currentHidden = file.is(VFileProperty.HIDDEN);
        boolean upToDateHidden = attributes.isHidden();
        if (currentHidden != upToDateHidden) {
          scheduleAttributeChange(file, VirtualFile.PROP_HIDDEN, currentHidden, upToDateHidden);
        }
      }

      if (attributes.isSymLink()) {
        String currentTarget = file.getCanonicalPath();
        String upToDateTarget = fs.resolveSymLink(file);
        String upToDateVfsTarget = upToDateTarget != null ? FileUtil.toSystemIndependentName(upToDateTarget) : null;
        if (!Comparing.equal(currentTarget, upToDateVfsTarget)) {
          scheduleAttributeChange(file, VirtualFile.PROP_SYMLINK_TARGET, currentTarget, upToDateVfsTarget);
        }
      }

      if (myIsRecursive || !file.isDirectory()) {
        file.markClean();
      }
    }
  }

  private void fullDirRefresh(NewVirtualFileSystem fs, PersistentFS persistence, TObjectHashingStrategy<String> strategy, VirtualDirectoryImpl dir) {
    while (true) {
      // obtaining directory snapshot
      Pair<String[], VirtualFile[]> result = LocalFileSystemRefreshWorker.getDirectorySnapshot(persistence, dir);
      if (result == null) return;
      String[] currentNames = result.getFirst();
      VirtualFile[] children = result.getSecond();

      // reading children attributes
      String[] upToDateNames = VfsUtil.filterNames(fs.list(dir));
      Set<String> newNames = newTroveSet(strategy, upToDateNames);
      ContainerUtil.removeAll(newNames, currentNames);
      Set<String> deletedNames = newTroveSet(strategy, currentNames);
      ContainerUtil.removeAll(deletedNames, upToDateNames);

      OpenTHashSet<String> actualNames = fs.isCaseSensitive() ? null : new OpenTHashSet<>(strategy, upToDateNames);
      if (LOG.isTraceEnabled()) LOG.trace("current=" + Arrays.toString(currentNames) + " +" + newNames + " -" + deletedNames);

      List<Pair<String, FileAttributes>> addedMap = ContainerUtil.newArrayListWithCapacity(newNames.size());
      for (String name : newNames) {
        checkCancelled(dir);
        addedMap.add(pair(name, fs.getAttributes(new FakeVirtualFile(dir, name))));
      }

      List<Pair<VirtualFile, FileAttributes>> updatedMap = ContainerUtil.newArrayListWithCapacity(children.length);
      for (VirtualFile child : children) {
        if (deletedNames.contains(child.getName())) continue;
        checkCancelled(dir);
        updatedMap.add(pair(child, fs.getAttributes(child)));
      }

      // generating events unless a directory was changed in between
      boolean hasEvents = ReadAction.compute(() -> {
        if (!Arrays.equals(currentNames, persistence.list(dir)) || !Arrays.equals(children, dir.getChildren())) {
          if (LOG.isTraceEnabled()) LOG.trace("retry: " + dir);
          return false;
        }

        for (String name : deletedNames) {
          scheduleDeletion(dir.findChild(name));
        }

        for (Pair<String, FileAttributes> pair : addedMap) {
          String name = pair.first;
          FileAttributes childAttributes = pair.second;
          if (childAttributes != null) {
            scheduleCreation(dir, name, childAttributes.isDirectory());
          }
          else {
            if (LOG.isTraceEnabled()) LOG.trace("[+] fs=" + fs + " dir=" + dir + " name=" + name);
          }
        }

        for (Pair<VirtualFile, FileAttributes> pair : updatedMap) {
          VirtualFile child = pair.first;
          FileAttributes childAttributes = pair.second;
          if (childAttributes != null) {
            checkAndScheduleChildRefresh(dir, child, childAttributes);
            checkAndScheduleFileNameChange(actualNames, child);
          }
          else {
            if (LOG.isTraceEnabled()) LOG.warn("[x] fs=" + fs + " dir=" + dir + " name=" + child.getName());
            scheduleDeletion(child);
          }
        }

        return true;
      });
      if (hasEvents) {
        break;
      }
    }
  }

  private void partialDirRefresh(NewVirtualFileSystem fs, TObjectHashingStrategy<String> strategy, VirtualDirectoryImpl dir) {
    while (true) {
      // obtaining directory snapshot
      Pair<List<VirtualFile>, List<String>> result =
        ReadAction.compute(() -> pair(dir.getCachedChildren(), dir.getSuspiciousNames()));

      List<VirtualFile> cached = result.getFirst();
      List<String> wanted = result.getSecond();

      OpenTHashSet<String> actualNames =
        fs.isCaseSensitive() || cached.isEmpty() ? null : new OpenTHashSet<>(strategy, VfsUtil.filterNames(fs.list(dir)));

      if (LOG.isTraceEnabled()) {
        LOG.trace("cached=" + cached + " actual=" + actualNames);
        LOG.trace("suspicious=" + wanted);
      }

      // reading children attributes
      List<Pair<VirtualFile, FileAttributes>> existingMap = ContainerUtil.newArrayListWithCapacity(cached.size());
      for (VirtualFile child : cached) {
        checkCancelled(dir);
        existingMap.add(pair(child, fs.getAttributes(child)));
      }

      List<Pair<String, FileAttributes>> wantedMap = ContainerUtil.newArrayListWithCapacity(wanted.size());
      for (String name : wanted) {
        if (name.isEmpty()) continue;
        checkCancelled(dir);
        wantedMap.add(pair(name, fs.getAttributes(new FakeVirtualFile(dir, name))));
      }

      // generating events unless a directory was changed in between
      boolean hasEvents = ReadAction.compute(() -> {
        if (!cached.equals(dir.getCachedChildren()) || !wanted.equals(dir.getSuspiciousNames())) {
          if (LOG.isTraceEnabled()) LOG.trace("retry: " + dir);
          return false;
        }

        for (Pair<VirtualFile, FileAttributes> pair : existingMap) {
          VirtualFile child = pair.first;
          FileAttributes childAttributes = pair.second;
          if (childAttributes != null) {
            checkAndScheduleChildRefresh(dir, child, childAttributes);
            checkAndScheduleFileNameChange(actualNames, child);
          }
          else {
            scheduleDeletion(child);
          }
        }

        for (Pair<String, FileAttributes> pair : wantedMap) {
          String name = pair.first;
          FileAttributes childAttributes = pair.second;
          if (childAttributes != null) {
            scheduleCreation(dir, name, childAttributes.isDirectory());
          }
        }

        return true;
      });
      if (hasEvents) {
        break;
      }
    }
  }

  private void checkAndScheduleFileNameChange(@Nullable OpenTHashSet<String> actualNames, VirtualFile child) {
    if (actualNames != null) {
      String currentName = child.getName();
      String actualName = actualNames.get(currentName);
      if (actualName != null && !currentName.equals(actualName)) {
        scheduleAttributeChange(child, VirtualFile.PROP_NAME, currentName, actualName);
      }
    }
  }

  private static class RefreshCancelledException extends RuntimeException { }

  private void checkCancelled(@NotNull NewVirtualFile stopAt) {
    if (myCancelled || ourCancellingCondition != null && ourCancellingCondition.fun(stopAt)) {
      if (LOG.isTraceEnabled()) LOG.trace("cancelled at: " + stopAt);
      forceMarkDirty(stopAt);
      while (!myRefreshQueue.isEmpty()) {
        NewVirtualFile next = myRefreshQueue.pullFirst().first;
        forceMarkDirty(next);
      }
      throw new RefreshCancelledException();
    }
  }

  private static void forceMarkDirty(NewVirtualFile file) {
    file.markClean();  // otherwise consequent markDirty() won't have any effect
    file.markDirty();
  }

  private void checkAndScheduleChildRefresh(@NotNull VirtualFile parent,
                                            @NotNull VirtualFile child,
                                            @NotNull FileAttributes childAttributes) {
    if (!checkAndScheduleFileTypeChange(parent, child, childAttributes)) {
      boolean upToDateIsDirectory = childAttributes.isDirectory();
      if (myIsRecursive || !upToDateIsDirectory) {
        myRefreshQueue.addLast(pair((NewVirtualFile)child, childAttributes));
      }
    }
  }

  private boolean checkAndScheduleFileTypeChange(@NotNull VirtualFile parent,
                                                 @NotNull VirtualFile child,
                                                 @NotNull FileAttributes childAttributes) {
    boolean currentIsDirectory = child.isDirectory();
    boolean currentIsSymlink = child.is(VFileProperty.SYMLINK);
    boolean currentIsSpecial = child.is(VFileProperty.SPECIAL);
    boolean upToDateIsDirectory = childAttributes.isDirectory();
    boolean upToDateIsSymlink = childAttributes.isSymLink();
    boolean upToDateIsSpecial = childAttributes.isSpecial();

    if (currentIsDirectory != upToDateIsDirectory || currentIsSymlink != upToDateIsSymlink || currentIsSpecial != upToDateIsSpecial) {
      scheduleDeletion(child);
      scheduleCreation(parent, child.getName(), upToDateIsDirectory);
      return true;
    }

    return false;
  }

  private void scheduleAttributeChange(@NotNull VirtualFile file, @VirtualFile.PropName @NotNull String property, Object current, Object upToDate) {
    if (LOG.isTraceEnabled()) LOG.trace("update '" + property + "' file=" + file);
    myEvents.add(new VFilePropertyChangeEvent(null, file, property, current, upToDate, true));
  }

  private void scheduleUpdateContent(@NotNull VirtualFile file) {
    if (LOG.isTraceEnabled()) LOG.trace("update file=" + file);
    myEvents.add(new VFileContentChangeEvent(null, file, file.getModificationStamp(), -1, true));
  }

  private void scheduleCreation(@NotNull VirtualFile parent, @NotNull String childName, boolean isDirectory) {
    if (LOG.isTraceEnabled()) LOG.trace("create parent=" + parent + " name=" + childName + " dir=" + isDirectory);
    myEvents.add(new VFileCreateEvent(null, parent, childName, isDirectory, true));
  }

  private void scheduleDeletion(@Nullable VirtualFile file) {
    if (file != null) {
      if (LOG.isTraceEnabled()) LOG.trace("delete file=" + file);
      myEvents.add(new VFileDeleteEvent(null, file, true));
    }
  }

  private static Function<VirtualFile, Boolean> ourCancellingCondition;

  @TestOnly
  public static void setCancellingCondition(@Nullable Function<VirtualFile, Boolean> condition) {
    assert ApplicationManager.getApplication().isUnitTestMode();
    LocalFileSystemRefreshWorker.setCancellingCondition(condition);
    ourCancellingCondition = condition;
  }
}