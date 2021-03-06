// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes;

import com.intellij.ide.highlighter.WorkspaceFileType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.*;
import com.intellij.openapi.components.impl.stores.IProjectStore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.BackgroundTaskUtil;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.VcsShowConfirmationOption.Value;
import com.intellij.openapi.vcs.changes.ChangeListWorker.ChangeListUpdater;
import com.intellij.openapi.vcs.changes.actions.ChangeListRemoveConfirmation;
import com.intellij.openapi.vcs.changes.actions.ScheduleForAdditionAction;
import com.intellij.openapi.vcs.changes.conflicts.ChangelistConflictTracker;
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager;
import com.intellij.openapi.vcs.changes.ui.ChangeListDeltaListener;
import com.intellij.openapi.vcs.changes.ui.DefaultCommitResultHandler;
import com.intellij.openapi.vcs.changes.ui.SingleChangeListCommitter;
import com.intellij.openapi.vcs.impl.AbstractVcsHelperImpl;
import com.intellij.openapi.vcs.impl.ContentRevisionCache;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsInitObject;
import com.intellij.openapi.vcs.readOnlyHandler.ReadonlyStatusHandlerImpl;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.project.ProjectKt;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.lang.CompoundRuntimeException;
import com.intellij.util.messages.Topic;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;
import kotlin.text.StringsKt;
import org.jdom.Element;
import org.jetbrains.annotations.*;

import javax.swing.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.intellij.openapi.vcs.ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED;
import static com.intellij.util.containers.ContainerUtil.emptyList;

@State(name = "ChangeListManager", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public class ChangeListManagerImpl extends ChangeListManagerEx implements ProjectComponent, ChangeListOwner, PersistentStateComponent<Element> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.ChangeListManagerImpl");
  private static final String EXCLUDED_CONVERTED_TO_IGNORED_OPTION = "EXCLUDED_CONVERTED_TO_IGNORED";

  public static final Topic<LocalChangeListsLoadedListener> LISTS_LOADED =
    new Topic<>("LOCAL_CHANGE_LISTS_LOADED", LocalChangeListsLoadedListener.class);

  private final Project myProject;
  private final VcsConfiguration myConfig;
  private final ChangesViewI myChangesViewManager;
  private final FileStatusManager myFileStatusManager;
  private final ChangelistConflictTracker myConflictTracker;
  private VcsDirtyScopeManager myDirtyScopeManager;

  private final Scheduler myScheduler = new Scheduler(); // update thread

  private final EventDispatcher<ChangeListListener> myListeners = EventDispatcher.create(ChangeListListener.class);
  private final DelayedNotificator myDelayedNotificator; // notifies myListeners on the update thread

  private final Object myDataLock = new Object();

  private final IgnoredFilesComponent myIgnoredIdeaLevel;
  private final UpdateRequestsQueue myUpdater;
  private final Modifier myModifier;
  private final MyChangesDeltaForwarder myDeltaForwarder;

  private FileHolderComposite myComposite;
  private final ChangeListWorker myWorker;

  private VcsException myUpdateException;
  private Factory<JComponent> myAdditionalInfo;
  private volatile boolean myShowLocalChangesInvalidated;

  @NotNull private ProgressIndicator myUpdateChangesProgressIndicator = createProgressIndicator();
  private volatile String myFreezeName;

  @NotNull private final Set<String> myListsToBeDeletedSilently = new HashSet<>();
  @NotNull private final Set<String> myListsToBeDeleted = new HashSet<>();
  private boolean myEmptyListDeletionScheduled;
  private boolean myModalNotificationsBlocked;

  private final List<CommitExecutor> myRegisteredCommitExecutors = new ArrayList<>();

  private boolean myExcludedConvertedToIgnored;

  public static ChangeListManagerImpl getInstanceImpl(final Project project) {
    return (ChangeListManagerImpl)getInstance(project);
  }

  void setDirtyScopeManager(VcsDirtyScopeManager dirtyScopeManager) {
    myDirtyScopeManager = dirtyScopeManager;
  }

  public ChangeListManagerImpl(@NotNull Project project, VcsConfiguration config) {
    myProject = project;
    myConfig = config;
    myChangesViewManager = myProject.isDefault() ? new DummyChangesView(myProject) : ChangesViewManager.getInstance(myProject);
    myFileStatusManager = FileStatusManager.getInstance(myProject);
    myConflictTracker = new ChangelistConflictTracker(project, this, myFileStatusManager, EditorNotifications.getInstance(project));

    myIgnoredIdeaLevel = new IgnoredFilesComponent(myProject, true);

    myComposite = new FileHolderComposite(project);
    myDeltaForwarder = new MyChangesDeltaForwarder(myProject, myScheduler);
    myDelayedNotificator = new DelayedNotificator(this, myListeners, myScheduler);
    myWorker = new ChangeListWorker(myProject, myDelayedNotificator);

    myUpdater = new UpdateRequestsQueue(myProject, myScheduler, () -> updateImmediately());
    myModifier = new Modifier(myWorker, myDelayedNotificator);

    myListeners.addListener(new ChangeListAdapter() {
      @Override
      public void defaultListChanged(ChangeList oldDefaultList, ChangeList newDefaultList, boolean automatic) {
        final LocalChangeList oldList = (LocalChangeList)oldDefaultList;
        if (automatic || oldDefaultList == null || oldList.hasDefaultName() || oldDefaultList.equals(newDefaultList)) return;

        scheduleAutomaticEmptyChangeListDeletion(oldList);
      }
    });

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      ProjectManager.getInstance().addProjectManagerListener(project, new ProjectManagerListener() {
        @Override
        public void projectClosing(@NotNull Project project) {
          //noinspection TestOnlyProblems
          waitEverythingDoneInTestMode();
        }
      });
    }
  }

  @Override
  public void scheduleAutomaticEmptyChangeListDeletion(@NotNull LocalChangeList list) {
    scheduleAutomaticEmptyChangeListDeletion(list, false);
  }

  @Override
  public void scheduleAutomaticEmptyChangeListDeletion(@NotNull LocalChangeList oldList, boolean silently) {
    synchronized (myDataLock) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Schedule empty changelist deletion: %s, silently = %s", oldList.getName(), silently));
      }

      if (silently) {
        myListsToBeDeletedSilently.add(oldList.getId());
      }
      else {
        myListsToBeDeleted.add(oldList.getId());
      }

      if (!myEmptyListDeletionScheduled) {
        myEmptyListDeletionScheduled = true;
        invokeAfterUpdate(() -> deleteEmptyChangeLists(), InvokeAfterUpdateMode.SILENT, null, null);
      }
    }
  }

  @CalledInAwt
  private void deleteEmptyChangeLists() {
    List<LocalChangeList> listsToBeDeletedSilently;
    List<LocalChangeList> listsToBeDeleted;

    Function<String, LocalChangeList> toDeleteMapping = id -> {
      LocalChangeList list = getChangeList(id);
      if (list == null || list.isDefault() || list.isReadOnly() || !list.getChanges().isEmpty()) return null;
      return list;
    };

    synchronized (myDataLock) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Empty changelist deletion, scheduled:\nsilently: %s\nasking: %s",
                                myListsToBeDeletedSilently, myListsToBeDeleted));
      }

      myListsToBeDeleted.removeAll(myListsToBeDeletedSilently);

      listsToBeDeletedSilently = ContainerUtil.mapNotNull(myListsToBeDeletedSilently, toDeleteMapping);
      myListsToBeDeletedSilently.clear();

      boolean askLater = myModalNotificationsBlocked &&
                         myConfig.REMOVE_EMPTY_INACTIVE_CHANGELISTS == Value.SHOW_CONFIRMATION;
      if (!askLater) {
        listsToBeDeleted = ContainerUtil.mapNotNull(myListsToBeDeleted, toDeleteMapping);
        myListsToBeDeleted.clear();
      }
      else {
        listsToBeDeleted = Collections.emptyList();
      }

      myEmptyListDeletionScheduled = false;

      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Empty changelist deletion, to be deleted:\nsilently: %s\nasking: %s",
                                listsToBeDeletedSilently, listsToBeDeleted));
      }
    }

    if (myConfig.REMOVE_EMPTY_INACTIVE_CHANGELISTS == Value.DO_NOTHING_SILENTLY ||
        myConfig.REMOVE_EMPTY_INACTIVE_CHANGELISTS == Value.SHOW_CONFIRMATION &&
        ApplicationManager.getApplication().isUnitTestMode()) {
      listsToBeDeleted.clear();
    }

    ChangeListRemoveConfirmation.deleteEmptyInactiveLists(myProject, listsToBeDeletedSilently, toAsk -> true);

    ChangeListRemoveConfirmation.deleteEmptyInactiveLists(myProject, listsToBeDeleted, toAsk -> {
      return myConfig.REMOVE_EMPTY_INACTIVE_CHANGELISTS == VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY ||
             showRemoveEmptyChangeListsProposal(myProject, myConfig, toAsk);
    });
  }

  /**
   * Shows the proposal to delete one or more changelists that were default and became empty.
   *
   * @return true if the changelists have to be deleted, false if not.
   */
  public static boolean showRemoveEmptyChangeListsProposal(@NotNull Project project,
                                                           @NotNull final VcsConfiguration config,
                                                           @NotNull Collection<? extends ChangeList> lists) {
    if (lists.isEmpty()) {
      return false;
    }

    final String question;
    if (lists.size() == 1) {
      question = String.format("<html>The empty changelist '%s' is no longer active.<br>Do you want to remove it?</html>",
                               StringUtil.first(lists.iterator().next().getName(), 30, true));
    }
    else {
      question = String.format("<html>Empty changelists<br/>%s are no longer active.<br>Do you want to remove them?</html>",
                               StringUtil.join(lists, list -> StringUtil.first(list.getName(), 30, true), "<br/>"));
    }

    VcsConfirmationDialog dialog = new VcsConfirmationDialog(project, "Remove Empty Changelist", "Remove", "Cancel", new VcsShowConfirmationOption() {
      @Override
      public Value getValue() {
        return config.REMOVE_EMPTY_INACTIVE_CHANGELISTS;
      }

      @Override
      public void setValue(Value value) {
        config.REMOVE_EMPTY_INACTIVE_CHANGELISTS = value;
      }

      @Override
      public boolean isPersistent() {
        return true;
      }
    }, question, "&Remember my choice");
    return dialog.showAndGet();
  }

  @Override
  @CalledInAwt
  public void blockModalNotifications() {
    myModalNotificationsBlocked = true;
  }

  @Override
  @CalledInAwt
  public void unblockModalNotifications() {
    myModalNotificationsBlocked = false;
    deleteEmptyChangeLists();
  }

  @Override
  public void projectOpened() {
    initializeForNewProject();

    VcsListener vcsListener = new VcsListener() {
      @Override
      public void directoryMappingChanged() {
        VcsDirtyScopeManager.getInstance(myProject).markEverythingDirty();
      }
    };

    final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      myUpdater.initialized();
      myProject.getMessageBus().connect().subscribe(VCS_CONFIGURATION_CHANGED, vcsListener);
    }
    else {
      ((ProjectLevelVcsManagerImpl)vcsManager).addInitializationRequest(
        VcsInitObject.CHANGE_LIST_MANAGER, (DumbAwareRunnable)() -> {
          myUpdater.initialized();
          broadcastStateAfterLoad();
          myProject.getMessageBus().connect().subscribe(VCS_CONFIGURATION_CHANGED, vcsListener);
        });

      myConflictTracker.startTracking();
    }
  }

  private void broadcastStateAfterLoad() {
    List<LocalChangeList> listCopy = getChangeListsCopy();
    if (!myProject.isDisposed()) {
      myProject.getMessageBus().syncPublisher(LISTS_LOADED).processLoadedLists(listCopy);
    }
  }

  @CalledInAwt
  private void initializeForNewProject() {
    synchronized (myDataLock) {
      if (!Registry.is("ide.hide.excluded.files") && !myExcludedConvertedToIgnored) {
        convertExcludedToIgnored();
        myExcludedConvertedToIgnored = true;
      }
    }
  }

  void convertExcludedToIgnored() {
    for (DirectoryIndexExcludePolicy policy : DirectoryIndexExcludePolicy.EP_NAME.getExtensions(myProject)) {
      for (String url : policy.getExcludeUrlsForProject()) {
        addDirectoryToIgnoreImplicitly(VfsUtilCore.urlToPath(url));
      }
    }

    ProjectFileIndex fileIndex = ProjectFileIndex.SERVICE.getInstance(myProject);
    VirtualFileManager virtualFileManager = VirtualFileManager.getInstance();
    for (Module module : ModuleManager.getInstance(myProject).getModules()) {
      for (String url : ModuleRootManager.getInstance(module).getExcludeRootUrls()) {
        VirtualFile file = virtualFileManager.findFileByUrl(url);
        if (file != null && !fileIndex.isExcluded(file)) {
          //root is included into some inner module so it shouldn't be ignored
          continue;
        }
        addDirectoryToIgnoreImplicitly(VfsUtilCore.urlToPath(url));
      }
    }
  }

  @Override
  public void projectClosed() {
    synchronized (myDataLock) {
      myUpdateChangesProgressIndicator.cancel();
    }

    myUpdater.stop();
    myConflictTracker.stopTracking();
  }

  @Override
  @NotNull @NonNls
  public String getComponentName() {
    return "ChangeListManager";
  }

  public void registerChangeTracker(@NotNull FilePath filePath, @NotNull ChangeListWorker.PartialChangeTracker tracker) {
    synchronized (myDataLock) {
      myWorker.registerChangeTracker(filePath, tracker);
    }
  }

  public void unregisterChangeTracker(@NotNull FilePath filePath, @NotNull ChangeListWorker.PartialChangeTracker tracker) {
    synchronized (myDataLock) {
      myWorker.unregisterChangeTracker(filePath, tracker);
    }
  }

  /**
   * update itself might produce actions done on AWT thread (invoked-after),
   * so waiting for its completion on AWT thread is not good runnable is invoked on AWT thread
   */
  @Override
  public void invokeAfterUpdate(@NotNull Runnable afterUpdate,
                                @NotNull InvokeAfterUpdateMode mode,
                                @Nullable String title,
                                @Nullable ModalityState state) {
    invokeAfterUpdate(afterUpdate, mode, title, null, state);
  }

  @Override
  public void invokeAfterUpdate(@NotNull Runnable afterUpdate,
                                @NotNull InvokeAfterUpdateMode mode,
                                @Nullable String title,
                                @Nullable Consumer<? super VcsDirtyScopeManager> dirtyScopeManagerFiller,
                                @Nullable ModalityState state) {
    if (dirtyScopeManagerFiller != null && !myProject.isDisposed()) {
      dirtyScopeManagerFiller.consume(VcsDirtyScopeManager.getInstance(myProject));
    }
    myUpdater.invokeAfterUpdate(afterUpdate, mode, title, state);
  }

  @Override
  public void freeze(@NotNull String reason) {
    assert !ApplicationManager.getApplication().isDispatchThread();

    myUpdater.setIgnoreBackgroundOperation(true);
    Semaphore sem = new Semaphore();
    sem.down();

    invokeAfterUpdate(() -> {
      myUpdater.setIgnoreBackgroundOperation(false);
      myUpdater.pause();
      myFreezeName = reason;
      sem.up();
    }, InvokeAfterUpdateMode.SILENT_CALLBACK_POOLED, "", ModalityState.defaultModalityState());

    boolean free = false;
    while (!free) {
      ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();
      if (pi != null) pi.checkCanceled();
      free = sem.waitFor(500);
    }
  }

  @Override
  public void unfreeze() {
    myUpdater.go();
    myFreezeName = null;
  }

  @Override
  public String isFreezed() {
    return myFreezeName;
  }

  public void executeOnUpdaterThread(@NotNull Runnable r) {
    myScheduler.submit(r);
  }

  public void executeUnderDataLock(@NotNull Runnable r) {
    ApplicationManager.getApplication().runReadAction(() -> {
      synchronized (myDataLock) {
        r.run();
      }
    });
  }

  @Override
  public void scheduleUpdate() {
    myUpdater.schedule();
  }

  @Override
  public void scheduleUpdate(boolean updateUnversionedFiles) {
    myUpdater.schedule();
  }

  private void filterOutIgnoredFiles(final List<VcsDirtyScope> scopes) {
    final Set<VirtualFile> refreshFiles = new HashSet<>();
    try {
      ReadAction.run(() -> {
        synchronized (myDataLock) {
          final IgnoredFilesCompositeHolder fileHolder = myComposite.getIgnoredFileHolder();

          for (Iterator<VcsDirtyScope> iterator = scopes.iterator(); iterator.hasNext(); ) {
            final VcsModifiableDirtyScope scope = (VcsModifiableDirtyScope)iterator.next();
            final VcsDirtyScopeModifier modifier = scope.getModifier();
            if (modifier == null) continue;

            fileHolder.notifyVcsStarted(scope.getVcs());

            filterOutIgnoredFiles(modifier.getDirtyFilesIterator(), fileHolder, refreshFiles);
            filterOutIgnoredFiles(modifier.getDirtyDirectoriesIterator(), fileHolder, refreshFiles);

            modifier.recheckDirtyKeys();

            if (scope.isEmpty()) {
              iterator.remove();
            }
          }
        }
      });
    }
    catch (ProcessCanceledException ignore) {
    }
    catch (Exception | AssertionError ex) {
      LOG.error(ex);
    }
    for (VirtualFile file : refreshFiles) {
      myFileStatusManager.fileStatusChanged(file);
    }
  }

  private void filterOutIgnoredFiles(Iterator<? extends FilePath> iterator,
                                     IgnoredFilesCompositeHolder fileHolder,
                                     Set<? super VirtualFile> refreshFiles) {
    while (iterator.hasNext()) {
      VirtualFile file = iterator.next().getVirtualFile();
      if (file != null && isPotentiallyIgnoredFile(file)) {
        AbstractVcs vcs = VcsUtil.getVcsFor(myProject, file);
        if (vcs != null) {
          iterator.remove();
          fileHolder.addFile(vcs, file);
          refreshFiles.add(file);
        }
      }
    }
  }

  private void updateImmediately() {
    final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
    if (!vcsManager.hasActiveVcss()) return;

    ProgressIndicator indicator = createProgressIndicator();
    synchronized (myDataLock) {
      myUpdateChangesProgressIndicator = indicator;
    }

    ProgressManager.getInstance().runProcess(() -> {
      if (myProject.isDisposed()) return;

      final VcsInvalidated invalidated = myDirtyScopeManager.retrieveScopes();
      if (checkScopeIsEmpty(invalidated)) {
        LOG.debug("[update] - dirty scope is empty");
        myDirtyScopeManager.changesProcessed();
        return;
      }

      final boolean wasEverythingDirty = invalidated.isEverythingDirty();
      final List<VcsDirtyScope> scopes = invalidated.getScopes();

      try {
        if (myUpdater.isStopped()) return;

        // copy existing data to objects that would be updated.
        // mark for "modifier" that update started (it would create duplicates of modification commands done by user during update;
        // after update of copies of objects is complete, it would apply the same modifications to copies.)
        final DataHolder dataHolder;
        synchronized (myDataLock) {
          dataHolder = new DataHolder(myComposite.copy(), new ChangeListUpdater(myWorker), wasEverythingDirty);
          myModifier.enterUpdate();
          if (wasEverythingDirty) {
            myUpdateException = null;
            myAdditionalInfo = null;
          }

          if (LOG.isDebugEnabled()) {
            String scopeInString = StringUtil.join(scopes, scope -> scope.toString(), "->\n");
            LOG.debug("refresh procedure started, everything: " + wasEverythingDirty + " dirty scope: " + scopeInString +
                      "\nignored: " + myComposite.getIgnoredFileHolder().values().size() +
                      "\nunversioned: " + myComposite.getVFHolder(FileHolder.HolderType.UNVERSIONED).getFiles().size() +
                      "\ncurrent changes: " + myWorker);
          }
        }
        dataHolder.notifyStart();
        myChangesViewManager.scheduleRefresh();

        iterateScopes(dataHolder, scopes, indicator);

        boolean takeChanges;
        synchronized (myDataLock) {
          takeChanges = myUpdateException == null;
        }
        if (takeChanges) {
          // update vcs ignored files
          updateIgnoredFiles(dataHolder.getComposite());
        }

        // for the case of project being closed we need a read action here -> to be more consistent
        ApplicationManager.getApplication().runReadAction(() -> {
          if (myProject.isDisposed()) return;
          clearCurrentRevisionsCache(invalidated);

          synchronized (myDataLock) {
            // do same modifications to change lists as was done during update + do delayed notifications
            dataHolder.notifyEnd();

            // update member from copy
            if (takeChanges) {
              ChangeListWorker updatedWorker = dataHolder.getChangeListUpdater().finish();
              myModifier.finishUpdate(updatedWorker);

              myWorker.applyChangesFromUpdate(updatedWorker, myDeltaForwarder);

              if (LOG.isDebugEnabled()) {
                LOG.debug("refresh procedure finished, unversioned size: " +
                          dataHolder.getComposite().getVFHolder(FileHolder.HolderType.UNVERSIONED).getFiles().size() +
                          "\nchanges: " + myWorker);
              }
              final boolean statusChanged = !myComposite.equals(dataHolder.getComposite());
              myComposite = dataHolder.getComposite();
              if (statusChanged) {
                myDelayedNotificator.unchangedFileStatusChanged();
              }
              LOG.debug("[update] - success");
            }
            else {
              myModifier.finishUpdate(null);
              LOG.debug("[update] - aborted");
            }
            myShowLocalChangesInvalidated = false;
          }
        });

        for (VcsDirtyScope scope : scopes) {
          AbstractVcs vcs = scope.getVcs();
          if (vcs != null && vcs.isTrackingUnchangedContent()) {
            scope.iterateExistingInsideScope(file -> {
              LastUnchangedContentTracker.markUntouched(file); //todo what if it has become dirty again during update?
              return true;
            });
          }
        }
      }
      catch (ProcessCanceledException e) {
        // OK, we're finishing all the stuff now.
      }
      catch (Exception | AssertionError ex) {
        LOG.error(ex);
      }
      finally {
        myDirtyScopeManager.changesProcessed();

        myDelayedNotificator.changeListUpdateDone();
        myChangesViewManager.scheduleRefresh();
      }
    }, indicator);
  }

  private boolean checkScopeIsEmpty(VcsInvalidated invalidated) {
    if (invalidated == null) return true;
    if (invalidated.isEverythingDirty()) return false;
    if (invalidated.isEmpty()) return true;

    filterOutIgnoredFiles(invalidated.getScopes());
    return invalidated.isEmpty();
  }

  private void iterateScopes(DataHolder dataHolder,
                             List<? extends VcsDirtyScope> scopes,
                             @NotNull ProgressIndicator indicator) {
    final ChangeListUpdater updater = dataHolder.getChangeListUpdater();
    // do actual requests about file statuses
    Getter<Boolean> disposedGetter = () -> myProject.isDisposed() || myUpdater.isStopped();
    final UpdatingChangeListBuilder builder = new UpdatingChangeListBuilder(updater,
                                                                            dataHolder.getComposite(), disposedGetter);

    for (final VcsDirtyScope scope : scopes) {
      indicator.checkCanceled();

      final AbstractVcs vcs = scope.getVcs();
      if (vcs == null) continue;

      myChangesViewManager.setBusy(true);

      actualUpdate(builder, scope, vcs, dataHolder, updater, indicator);

      synchronized (myDataLock) {
        if (myUpdateException != null) break;
      }
    }
    synchronized (myDataLock) {
      if (myAdditionalInfo == null) {
        myAdditionalInfo = builder.getAdditionalInfo();
      }
    }
  }

  private void clearCurrentRevisionsCache(final VcsInvalidated invalidated) {
    final ContentRevisionCache cache = ProjectLevelVcsManager.getInstance(myProject).getContentRevisionCache();
    if (invalidated.isEverythingDirty()) {
      cache.clearAllCurrent();
    }
    else {
      cache.clearScope(invalidated.getScopes());
    }
  }

  @NotNull
  private static ProgressIndicator createProgressIndicator() {
    return new EmptyProgressIndicator();
  }

  private class DataHolder {
    private final boolean myWasEverythingDirty;
    private final FileHolderComposite myComposite;
    private final ChangeListUpdater myChangeListUpdater;

    private DataHolder(FileHolderComposite composite, ChangeListUpdater changeListUpdater, boolean wasEverythingDirty) {
      myComposite = composite;
      myChangeListUpdater = changeListUpdater;
      myWasEverythingDirty = wasEverythingDirty;
    }

    private void notifyStart() {
      if (myWasEverythingDirty) {
        myComposite.cleanAll();
        myChangeListUpdater.notifyStartProcessingChanges(null);
      }
    }

    private void notifyStartProcessingChanges(@NotNull final VcsModifiableDirtyScope scope) {
      if (!myWasEverythingDirty) {
        myComposite.cleanAndAdjustScope(scope);
        myChangeListUpdater.notifyStartProcessingChanges(scope);
      }

      myComposite.notifyVcsStarted(scope.getVcs());
    }

    private void notifyDoneProcessingChanges() {
      if (!myWasEverythingDirty) {
        myChangeListUpdater.notifyDoneProcessingChanges(myDelayedNotificator);
      }
    }

    void notifyEnd() {
      if (myWasEverythingDirty) {
        myChangeListUpdater.notifyDoneProcessingChanges(myDelayedNotificator);
      }
    }

    public FileHolderComposite getComposite() {
      return myComposite;
    }

    public ChangeListUpdater getChangeListUpdater() {
      return myChangeListUpdater;
    }
  }

  private void actualUpdate(@NotNull UpdatingChangeListBuilder builder,
                            @NotNull VcsDirtyScope scope,
                            @NotNull AbstractVcs vcs,
                            @NotNull DataHolder dataHolder,
                            @NotNull ChangeListManagerGate gate,
                            @NotNull ProgressIndicator indicator) {
    dataHolder.notifyStartProcessingChanges((VcsModifiableDirtyScope)scope);
    try {
      final ChangeProvider changeProvider = vcs.getChangeProvider();
      if (changeProvider != null) {
        builder.setCurrent(scope);
        changeProvider.getChanges(scope, builder, indicator, gate);
      }
    }
    catch (VcsException e) {
      handleUpdateException(e);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Throwable t) {
      LOG.debug(t);
      ExceptionUtil.rethrowAllAsUnchecked(t);
    }
    finally {
      if (!myUpdater.isStopped()) {
        dataHolder.notifyDoneProcessingChanges();
      }
    }
  }

  private void handleUpdateException(final VcsException e) {
    LOG.info(e);

    if (e instanceof VcsConnectionProblem) {
      ApplicationManager.getApplication().invokeLater(() -> ((VcsConnectionProblem)e).attemptQuickFix(false));
    }

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      AbstractVcsHelper helper = AbstractVcsHelper.getInstance(myProject);
      if (helper instanceof AbstractVcsHelperImpl && ((AbstractVcsHelperImpl)helper).handleCustom(e)) {
        return;
      }
      //noinspection CallToPrintStackTrace
      e.printStackTrace();
    }

    synchronized (myDataLock) {
      myUpdateException = e;
    }
  }

  public static boolean isUnder(final Change change, final VcsDirtyScope scope) {
    final ContentRevision before = change.getBeforeRevision();
    final ContentRevision after = change.getAfterRevision();
    return before != null && scope.belongsTo(before.getFile()) || after != null && scope.belongsTo(after.getFile());
  }

  @Override
  @NotNull
  public List<LocalChangeList> getChangeLists() {
    synchronized (myDataLock) {
      return myWorker.getChangeLists();
    }
  }

  @NotNull
  @Override
  public List<File> getAffectedPaths() {
    synchronized (myDataLock) {
      return myWorker.getAffectedPaths();
    }
  }

  @Override
  @NotNull
  public List<VirtualFile> getAffectedFiles() {
    synchronized (myDataLock) {
      return myWorker.getAffectedFiles();
    }
  }

  @Override
  @NotNull
  public Collection<Change> getAllChanges() {
    synchronized (myDataLock) {
      return myWorker.getAllChanges();
    }
  }

  @NotNull
  public List<VirtualFile> getUnversionedFiles() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getVFHolder(FileHolder.HolderType.UNVERSIONED).getFiles();
      }
    });
  }

  @NotNull
  @Override
  public List<VirtualFile> getModifiedWithoutEditing() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getVFHolder(FileHolder.HolderType.MODIFIED_WITHOUT_EDITING).getFiles();
      }
    });
  }

  /**
   * @return only roots for ignored folders, and ignored files
   */
  @NotNull
  public List<VirtualFile> getIgnoredFiles() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return new ArrayList<>(myComposite.getIgnoredFileHolder().values());
      }
    });
  }

  boolean isIgnoredInUpdateMode() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getIgnoredFileHolder().isInUpdatingMode();
      }
    });
  }

  public List<VirtualFile> getLockedFolders() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getVFHolder(FileHolder.HolderType.LOCKED).getFiles();
      }
    });
  }

  Map<VirtualFile, LogicalLock> getLogicallyLockedFolders() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return new HashMap<>(myComposite.getLogicallyLockedFileHolder().getMap());
      }
    });
  }

  public boolean isLogicallyLocked(final VirtualFile file) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getLogicallyLockedFileHolder().containsKey(file);
      }
    });
  }

  public boolean isContainedInLocallyDeleted(final FilePath filePath) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getDeletedFileHolder().isContainedInLocallyDeleted(filePath);
      }
    });
  }

  public List<LocallyDeletedChange> getDeletedFiles() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getDeletedFileHolder().getFiles();
      }
    });
  }

  MultiMap<String, VirtualFile> getSwitchedFilesMap() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getSwitchedFileHolder().getBranchToFileMap();
      }
    });
  }

  @Nullable
  Map<VirtualFile, String> getSwitchedRoots() {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getRootSwitchFileHolder().getFilesMapCopy();
      }
    });
  }

  public VcsException getUpdateException() {
    synchronized (myDataLock) {
      return myUpdateException;
    }
  }

  Factory<JComponent> getAdditionalUpdateInfo() {
    synchronized (myDataLock) {
      return myAdditionalInfo;
    }
  }

  @Override
  public boolean isFileAffected(@NotNull VirtualFile file) {
    synchronized (myDataLock) {
      return myWorker.getStatus(file) != null;
    }
  }

  @Override
  @Nullable
  public LocalChangeList findChangeList(final String name) {
    synchronized (myDataLock) {
      return myWorker.getChangeListByName(name);
    }
  }

  @Override
  public LocalChangeList getChangeList(String id) {
    synchronized (myDataLock) {
      return myWorker.getChangeListById(id);
    }
  }

  @Override
  public LocalChangeList addChangeList(@NotNull final String name, @Nullable final String comment) {
    return addChangeList(name, comment, null);
  }

  @NotNull
  @Override
  public LocalChangeList addChangeList(@NotNull final String name, @Nullable final String comment, @Nullable final ChangeListData data) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        final LocalChangeList changeList = myModifier.addChangeList(name, comment, data);
        myChangesViewManager.scheduleRefresh();
        return changeList;
      }
    });
  }


  @Override
  public void removeChangeList(@NotNull String name) {
    ApplicationManager.getApplication().runReadAction(() -> {
      synchronized (myDataLock) {
        myModifier.removeChangeList(name);
        myChangesViewManager.scheduleRefresh();
      }
    });
  }

  @Override
  public void removeChangeList(@NotNull LocalChangeList list) {
    removeChangeList(list.getName());
  }

  public void setDefaultChangeList(@NotNull String name, boolean automatic) {
    ApplicationManager.getApplication().runReadAction(() -> {
      synchronized (myDataLock) {
        myModifier.setDefault(name, automatic);
      }
    });
    myChangesViewManager.scheduleRefresh();
  }

  @Override
  public void setDefaultChangeList(@NotNull String name) {
    setDefaultChangeList(name, false);
  }

  @Override
  public void setDefaultChangeList(@NotNull final LocalChangeList list) {
    setDefaultChangeList(list, false);
  }

  @Override
  public void setDefaultChangeList(@NotNull final LocalChangeList list, boolean automatic) {
    setDefaultChangeList(list.getName(), automatic);
  }

  @Override
  public boolean setReadOnly(@NotNull String name, final boolean value) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        final boolean result = myModifier.setReadOnly(name, value);
        myChangesViewManager.scheduleRefresh();
        return result;
      }
    });
  }

  @Override
  public boolean editName(@NotNull final String fromName, @NotNull final String toName) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        final boolean result = myModifier.editName(fromName, toName);
        myChangesViewManager.scheduleRefresh();
        return result;
      }
    });
  }

  @Override
  public String editComment(@NotNull String name, String newComment) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        final String oldComment = myModifier.editComment(name, StringUtil.notNullize(newComment));
        myChangesViewManager.scheduleRefresh();
        return oldComment;
      }
    });
  }

  @Override
  public boolean editChangeListData(@NotNull String name, @Nullable ChangeListData newData) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        final boolean result = myModifier.editData(name, newData);
        myChangesViewManager.scheduleRefresh();
        return result;
      }
    });
  }

  @Override
  public void moveChangesTo(@NotNull LocalChangeList list, @NotNull Change... changes) {
    ApplicationManager.getApplication().runReadAction(() -> {
      synchronized (myDataLock) {
        myModifier.moveChangesTo(list.getName(), changes);
      }
    });
    myChangesViewManager.scheduleRefresh();
  }

  @NotNull
  @Override
  public LocalChangeList getDefaultChangeList() {
    synchronized (myDataLock) {
      return myWorker.getDefaultList();
    }
  }

  @NotNull
  @Override
  public String getDefaultListName() {
    synchronized (myDataLock) {
      return myWorker.getDefaultList().getName();
    }
  }

  public void notifyChangelistsChanged(@NotNull FilePath path,
                                       @NotNull List<String> beforeChangeListsIds,
                                       @NotNull List<String> afterChangeListsIds) {
    myWorker.notifyChangelistsChanged(path, beforeChangeListsIds, afterChangeListsIds);
  }

  @Override
  public String getChangeListNameIfOnlyOne(final Change[] changes) {
    synchronized (myDataLock) {
      List<LocalChangeList> lists = myWorker.getAffectedLists(Arrays.asList(changes));
      return lists.size() == 1 ? lists.get(0).getName() : null;
    }
  }

  @Override
  public boolean isInUpdate() {
    return myModifier.isInsideUpdate() || myShowLocalChangesInvalidated;
  }

  @Override
  @Nullable
  public Change getChange(@NotNull VirtualFile file) {
    return getChange(VcsUtil.getFilePath(file));
  }

  @Override
  @NotNull
  public List<LocalChangeList> getAffectedLists(@NotNull Collection<? extends Change> changes) {
    synchronized (myDataLock) {
      return myWorker.getAffectedLists(changes);
    }
  }

  @NotNull
  @Override
  public List<LocalChangeList> getChangeLists(@NotNull Change change) {
    return getAffectedLists(Collections.singletonList(change));
  }

  @NotNull
  @Override
  public List<LocalChangeList> getChangeLists(@NotNull VirtualFile file) {
    synchronized (myDataLock) {
      Change change = myWorker.getChangeForPath(VcsUtil.getFilePath(file));
      if (change == null) return Collections.emptyList();
      return getChangeLists(change);
    }
  }

  @Override
  @Nullable
  public LocalChangeList getChangeList(@NotNull Change change) {
    return ContainerUtil.getFirstItem(getChangeLists(change));
  }

  @Override
  @Nullable
  public LocalChangeList getChangeList(@NotNull VirtualFile file) {
    return ContainerUtil.getFirstItem(getChangeLists(file));
  }

  @Override
  @Nullable
  public Change getChange(final FilePath file) {
    synchronized (myDataLock) {
      return myWorker.getChangeForPath(file);
    }
  }

  @Override
  public boolean isUnversioned(VirtualFile file) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getVFHolder(FileHolder.HolderType.UNVERSIONED).containsFile(file);
      }
    });
  }

  @Override
  @NotNull
  public FileStatus getStatus(@NotNull VirtualFile file) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        if (myComposite.getVFHolder(FileHolder.HolderType.UNVERSIONED).containsFile(file)) return FileStatus.UNKNOWN;
        if (myComposite.getVFHolder(FileHolder.HolderType.MODIFIED_WITHOUT_EDITING).containsFile(file)) return FileStatus.HIJACKED;
        if (myComposite.getIgnoredFileHolder().containsFile(file)) return FileStatus.IGNORED;

        final FileStatus status = ObjectUtils.notNull(myWorker.getStatus(file), FileStatus.NOT_CHANGED);

        if (FileStatus.NOT_CHANGED.equals(status)) {
          boolean switched = myComposite.getSwitchedFileHolder().containsFile(file);
          if (switched) return FileStatus.SWITCHED;
        }

        return status;
      }
    });
  }

  @Override
  @NotNull
  public Collection<Change> getChangesIn(@NotNull VirtualFile dir) {
    return getChangesIn(VcsUtil.getFilePath(dir));
  }

  @NotNull
  @Override
  public ThreeState haveChangesUnder(@NotNull final VirtualFile vf) {
    if (!vf.isValid() || !vf.isDirectory()) return ThreeState.NO;
    synchronized (myDataLock) {
      return myWorker.haveChangesUnder(vf);
    }
  }

  @Override
  @NotNull
  public Collection<Change> getChangesIn(@NotNull FilePath dirPath) {
    synchronized (myDataLock) {
      return myWorker.getChangesUnder(dirPath);
    }
  }

  @Override
  @Nullable
  public AbstractVcs getVcsFor(@NotNull Change change) {
    synchronized (myDataLock) {
      return myWorker.getVcsFor(change);
    }
  }

  @Override
  public void addUnversionedFiles(@NotNull final LocalChangeList list, @NotNull final List<? extends VirtualFile> files) {
    ScheduleForAdditionAction.addUnversionedFilesToVcs(myProject, list, files);
  }

  @Override
  public Project getProject() {
    return myProject;
  }

  @Override
  public void addChangeListListener(@NotNull ChangeListListener listener, @NotNull Disposable disposable) {
    myListeners.addListener(listener, disposable);
  }

  @Override
  public void addChangeListListener(@NotNull ChangeListListener listener) {
    myListeners.addListener(listener);
  }

  @Override
  public void removeChangeListListener(@NotNull ChangeListListener listener) {
    myListeners.removeListener(listener);
  }

  @Override
  public void registerCommitExecutor(@NotNull CommitExecutor executor) {
    myRegisteredCommitExecutors.add(executor);
  }

  @Override
  public void commitChanges(@NotNull LocalChangeList changeList, @NotNull List<? extends Change> changes) {
    doCommit(changeList, changes, false);
  }

  private void doCommit(final LocalChangeList changeList, final List<? extends Change> changes, final boolean synchronously) {
    FileDocumentManager.getInstance().saveAllDocuments();

    String commitMessage = StringUtil.isEmpty(changeList.getComment()) ? changeList.getName() : changeList.getComment();
    SingleChangeListCommitter committer =
      new SingleChangeListCommitter(myProject, changeList, changes, commitMessage, emptyList(), FunctionUtil.nullConstant(), null,
                                    changeList.getName(), false);

    committer.addResultHandler(new DefaultCommitResultHandler(committer));
    committer.runCommit(changeList.getName(), synchronously);
  }

  @TestOnly
  public void commitChangesSynchronouslyWithResult(@NotNull LocalChangeList changeList, @NotNull List<? extends Change> changes) {
    doCommit(changeList, changes, true);
  }

  @Override
  public void loadState(@NotNull Element element) {
    if (myProject.isDefault()) {
      return;
    }

    synchronized (myDataLock) {
      ChangeListManagerSerialization.readExternal(element, myIgnoredIdeaLevel, myWorker);
    }
    myExcludedConvertedToIgnored = Boolean.parseBoolean(JDOMExternalizerUtil.readField(element, EXCLUDED_CONVERTED_TO_IGNORED_OPTION));
    myConflictTracker.loadState(element);
  }

  @Nullable
  @Override
  public Element getState() {
    Element element = new Element("state");
    if (myProject.isDefault()) {
      return element;
    }

    final IgnoredFilesComponent ignoredFilesComponent;
    final ChangeListWorker worker;
    synchronized (myDataLock) {
      ignoredFilesComponent = myIgnoredIdeaLevel.copy();
      worker = myWorker.copy();
    }
    ChangeListManagerSerialization.writeExternal(element, ignoredFilesComponent, worker);
    JDOMExternalizerUtil.writeField(element, EXCLUDED_CONVERTED_TO_IGNORED_OPTION, Boolean.toString(myExcludedConvertedToIgnored), Boolean.toString(false));
    myConflictTracker.saveState(element);
    return element;
  }

  // used in TeamCity
  @Override
  public void reopenFiles(@NotNull List<? extends FilePath> paths) {
    final ReadonlyStatusHandlerImpl readonlyStatusHandler = (ReadonlyStatusHandlerImpl)ReadonlyStatusHandler.getInstance(myProject);
    final boolean savedOption = readonlyStatusHandler.getState().SHOW_DIALOG;
    readonlyStatusHandler.getState().SHOW_DIALOG = false;
    try {
      readonlyStatusHandler.ensureFilesWritable(ContainerUtil.mapNotNull(paths, FilePath::getVirtualFile));
    }
    finally {
      readonlyStatusHandler.getState().SHOW_DIALOG = savedOption;
    }
  }

  @NotNull
  @Override
  public List<CommitExecutor> getRegisteredExecutors() {
    return Collections.unmodifiableList(myRegisteredCommitExecutors);
  }

  @Override
  public void addFilesToIgnore(@NotNull IgnoredFileBean... filesToIgnore) {
    myIgnoredIdeaLevel.add(filesToIgnore);
    scheduleUnversionedUpdate();
  }

  @Override
  public void addDirectoryToIgnoreImplicitly(@NotNull String path) {
    myIgnoredIdeaLevel.addIgnoredDirectoryImplicitly(path, myProject);
  }

  @Override
  public void removeImplicitlyIgnoredDirectory(@NotNull String path) {
    myIgnoredIdeaLevel.removeImplicitlyIgnoredDirectory(path, myProject);
  }

  /**
   * @deprecated All potential ignores should be contributed to VCS native ignores by corresponding {@link IgnoredFileProvider}.
   */
  @Deprecated
  public IgnoredFilesComponent getIgnoredFilesComponent() {
    return myIgnoredIdeaLevel;
  }

  private void scheduleUnversionedUpdate() {
    Couple<Collection<VirtualFile>> couple = ReadAction.compute(() -> {
      synchronized (myDataLock) {
        Collection<VirtualFile> unversioned = myComposite.getVFHolder(FileHolder.HolderType.UNVERSIONED).getFiles();
        Collection<VirtualFile> ignored = myComposite.getIgnoredFileHolder().values();
        return Couple.of(unversioned, ignored);
      }
    });

    Collection<VirtualFile> unversioned = couple.first;
    Collection<VirtualFile> ignored = couple.second;

    VcsDirtyScopeManager vcsDirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);

    final int ourPiecesLimit = 100;
    if (unversioned.size() + ignored.size() > ourPiecesLimit) {
      vcsDirtyScopeManager.markEverythingDirty();
    }
    else {
      List<VirtualFile> dirs = new ArrayList<>();
      List<VirtualFile> files = new ArrayList<>();

      for (VirtualFile vf : ContainerUtil.concat(unversioned, ignored)) {
        if (vf.isDirectory()) {
          dirs.add(vf);
        }
        else {
          files.add(vf);
        }
      }

      vcsDirtyScopeManager.filesDirty(files, dirs);
    }
  }

  @Override
  public void setFilesToIgnore(@NotNull IgnoredFileBean... filesToIgnore) {
    myIgnoredIdeaLevel.set(filesToIgnore);
    scheduleUnversionedUpdate();
  }

  private void updateIgnoredFiles(FileHolderComposite composite) {
    final VirtualFileHolder vfHolder = composite.getVFHolder(FileHolder.HolderType.UNVERSIONED);
    final List<VirtualFile> unversionedFiles = vfHolder.getFiles();
    exchangeWithIgnored(composite, vfHolder, unversionedFiles);

    final VirtualFileHolder vfModifiedHolder = composite.getVFHolder(FileHolder.HolderType.MODIFIED_WITHOUT_EDITING);
    final List<VirtualFile> modifiedFiles = vfModifiedHolder.getFiles();
    exchangeWithIgnored(composite, vfModifiedHolder, modifiedFiles);
  }

  private void exchangeWithIgnored(FileHolderComposite composite, VirtualFileHolder vfHolder, List<? extends VirtualFile> unversionedFiles) {
    for (VirtualFile file : unversionedFiles) {
      if (isPotentiallyIgnoredFile(file)) {
        AbstractVcs vcs = VcsUtil.getVcsFor(myProject, file);
        if (vcs != null) {
          vfHolder.removeFile(file);
          composite.getIgnoredFileHolder().addFile(vcs, file);
        }
      }
    }
  }

  @NotNull
  @Override
  public IgnoredFileBean[] getFilesToIgnore() {
    return myIgnoredIdeaLevel.getFilesToIgnore();
  }

  @NotNull
  @Override
  public Set<IgnoredFileDescriptor> getPotentiallyIgnoredFiles() {
    return ContainerUtil.unmodifiableOrEmptySet(
      IgnoredFileProvider.IGNORE_FILE.extensions()
        .flatMap(provider -> provider.getIgnoredFiles(myProject).stream())
        .collect(Collectors.toSet())
    );
  }

  @Override
  public boolean isIgnoredFile(@NotNull VirtualFile file) {
    return isPotentiallyIgnoredFile(file);
  }

  @Override
  public boolean isPotentiallyIgnoredFile(@NotNull VirtualFile file) {
    FilePath filePath = VcsUtil.getFilePath(file);
    return ContainerUtil.exists(IgnoredFileProvider.IGNORE_FILE.getExtensions(), it -> it.isIgnoredFile(myProject, filePath));
  }

  @Override
  public boolean isVcsIgnoredFile(@NotNull VirtualFile file) {
    synchronized (myDataLock) {
      return myComposite.getIgnoredFileHolder().containsFile(file);
    }
  }

  public static class DefaultIgnoredFileProvider implements IgnoredFileProvider {

    @Override
    public boolean isIgnoredFile(@NotNull Project project, @NotNull FilePath filePath) {
      IProjectStore store = ProjectKt.getStateStore(project);
      return (!ProjectKt.isDirectoryBased(project) && FileUtilRt.extensionEquals(filePath.getPath(), WorkspaceFileType.DEFAULT_EXTENSION))
             || StringsKt.equals(filePath.getPath(), store.getWorkspaceFilePath(), !SystemInfo.isFileSystemCaseSensitive)
             || isShelfDirOrInsideIt(filePath, project);
    }

    private static boolean isShelfDirOrInsideIt(@NotNull FilePath filePath, @NotNull Project project){
      String shelfPath = ShelveChangesManager.getShelfPath(project);
      return FileUtil.isAncestor(shelfPath, filePath.getPath(), false);
    }

    @NotNull
    @Override
    public Set<IgnoredFileDescriptor> getIgnoredFiles(@NotNull Project project) {
      Set<IgnoredFileBean> ignored = ContainerUtil.newLinkedHashSet();

      String shelfPath = ShelveChangesManager.getShelfPath(project);
      ignored.add(IgnoredBeanFactory.ignoreUnderDirectory(shelfPath, project));

      String workspaceFilePath = ProjectKt.getStateStore(project).getWorkspaceFilePath();
      if (workspaceFilePath != null) {
        ignored.add(IgnoredBeanFactory.ignoreFile(workspaceFilePath, project));
      }

      return ContainerUtil.unmodifiableOrEmptySet(ignored);
    }

    @NotNull
    @Override
    public String getIgnoredGroupDescription() {
      return "Default ignored files";
    }
  }

  @Override
  @Nullable
  public String getSwitchedBranch(@NotNull VirtualFile file) {
    return ReadAction.compute(() -> {
      synchronized (myDataLock) {
        return myComposite.getSwitchedFileHolder().getBranchForFile(file);
      }
    });
  }

  @TestOnly
  public void waitUntilRefreshed() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    VcsDirtyScopeVfsListener.getInstance(myProject).flushDirt();
    myUpdater.waitUntilRefreshed();
    waitUpdateAlarm();
  }

  @TestOnly
  private void waitUpdateAlarm() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    final Semaphore semaphore = new Semaphore();
    semaphore.down();
    myScheduler.submit(() -> semaphore.up());
    if (ApplicationManager.getApplication().isDispatchThread()) {
      while (!semaphore.waitFor(100)) {
        UIUtil.dispatchAllInvocationEvents();
      }
    } else {
      semaphore.waitFor();
    }
  }

  @TestOnly
  public void stopEveryThingIfInTestMode() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myScheduler.cancelAll();
  }

  @TestOnly
  public void waitEverythingDoneInTestMode() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myScheduler.awaitAll();
  }

  @TestOnly
  public void forceStopInTestMode() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myUpdater.stop();
  }

  @TestOnly
  public void forceGoInTestMode() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myUpdater.forceGo();
  }

  @TestOnly
  public boolean ensureUpToDate() {
    assert ApplicationManager.getApplication().isUnitTestMode();
    if (ApplicationManager.getApplication().isDispatchThread()) {
      updateImmediately();
      return true;
    }
    VcsDirtyScopeVfsListener.getInstance(myProject).flushDirt();
    myUpdater.waitUntilRefreshed();
    waitUpdateAlarm();
    return true;
  }

  @Override
  public int getChangeListsNumber() {
    synchronized (myDataLock) {
      return myWorker.getChangeListsNumber();
    }
  }

  // only a light attempt to show that some dirty scope request is asynchronously coming
  // for users to see changes are not valid
  // (commit -> asynch synch VFS -> asynch vcs dirty scope)
  public void showLocalChangesInvalidated() {
    myShowLocalChangesInvalidated = true;
  }

  public ChangelistConflictTracker getConflictTracker() {
    return myConflictTracker;
  }

  private static class MyChangesDeltaForwarder implements ChangeListDeltaListener {
    private final RemoteRevisionsCache myRevisionsCache;
    private final ProjectLevelVcsManager myVcsManager;
    private final Project myProject;
    private final ChangeListManagerImpl.Scheduler myScheduler;

    MyChangesDeltaForwarder(final Project project, @NotNull ChangeListManagerImpl.Scheduler scheduler) {
      myProject = project;
      myScheduler = scheduler;
      myRevisionsCache = RemoteRevisionsCache.getInstance(project);
      myVcsManager = ProjectLevelVcsManager.getInstance(project);
    }

    @Override
    public void modified(@NotNull BaseRevision was, @NotNull BaseRevision become) {
      doModify(was, become);
    }

    @Override
    public void added(@NotNull BaseRevision baseRevision) {
      doModify(baseRevision, baseRevision);
    }

    @Override
    public void removed(@NotNull BaseRevision baseRevision) {
       myScheduler.submit(() -> {
         AbstractVcs vcs = getVcs(baseRevision);
         if (vcs != null) {
           myRevisionsCache.changeRemoved(baseRevision.getPath(), vcs);
         }
         BackgroundTaskUtil.syncPublisher(myProject, VcsAnnotationRefresher.LOCAL_CHANGES_CHANGED).dirty(baseRevision.getPath());
       });
     }

    private void doModify(BaseRevision was, BaseRevision become) {
      myScheduler.submit(() -> {
        final AbstractVcs vcs = getVcs(was);
        if (vcs != null) {
          myRevisionsCache.changeUpdated(was.getPath(), vcs);
        }
        BackgroundTaskUtil.syncPublisher(myProject, VcsAnnotationRefresher.LOCAL_CHANGES_CHANGED).dirty(become);
      });
    }

    @Nullable
    private AbstractVcs getVcs(@NotNull BaseRevision baseRevision) {
      AbstractVcs vcs = baseRevision.getVcs();
      if (vcs != null) return vcs;
      return myVcsManager.getVcsFor(baseRevision.getFilePath());
    }
  }

  @Override
  public boolean isFreezedWithNotification(@Nullable String modalTitle) {
    final String freezeReason = isFreezed();
    if (freezeReason == null) return false;

    if (modalTitle != null) {
      Messages.showErrorDialog(myProject, freezeReason, modalTitle);
    }
    else {
      VcsBalloonProblemNotifier.showOverChangesView(myProject, freezeReason, MessageType.WARNING);
    }
    return true;
  }

  public void replaceCommitMessage(@NotNull String oldMessage, @NotNull String newMessage) {
    myConfig.replaceMessage(oldMessage, newMessage);

    for (LocalChangeList changeList : getChangeLists()) {
      if (oldMessage.equals(changeList.getComment())) {
        editComment(changeList.getName(), newMessage);
      }
    }
  }

  static class Scheduler {
    private final ScheduledExecutorService myExecutor =
      AppExecutorUtil.createBoundedScheduledExecutorService("ChangeListManagerImpl Pool", 1);

    // @TestOnly
    private final boolean myUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();
    private final ArrayDeque<Future> myFutures = new ArrayDeque<>();

    public void schedule(@NotNull Runnable command, long delay, @NotNull TimeUnit unit) {
      ScheduledFuture<?> future = myExecutor.schedule(command, delay, unit);
      if (myUnitTestMode) addFuture(future);
    }

    public void submit(@NotNull Runnable command) {
      Future<?> future = myExecutor.submit(command);
      if (myUnitTestMode) addFuture(future);
    }

    private void addFuture(Future<?> future) {
      assert ApplicationManager.getApplication().isUnitTestMode();
      synchronized (myFutures) {
        myFutures.add(future);
      }
    }

    @TestOnly
    private void cancelAll() {
      synchronized (myFutures) {
        for (Future future : myFutures) {
          future.cancel(true);
        }
        myFutures.clear();
      }
    }

    @TestOnly
    private void awaitAll() {
      List<Throwable> throwables = new ArrayList<>();

      long start = System.currentTimeMillis();
      while (true) {
        if (System.currentTimeMillis() - start > TimeUnit.MINUTES.toMillis(10)) {
          cancelAll();
          throwables.add(new IllegalStateException("Too long waiting for VCS update"));
          break;
        }
        Future future;
        synchronized (myFutures) {
          future = myFutures.peek();
        }
        if (future == null) break;

        if (ApplicationManager.getApplication().isDispatchThread()) {
          UIUtil.dispatchAllInvocationEvents();
        }

        try {
          future.get(10, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ignore) {
          continue;
        }
        catch (CancellationException ignored) {
        }
        catch (InterruptedException | ExecutionException e) {
          throwables.add(e);
        }

        synchronized (myFutures) {
          myFutures.remove(future);
        }
      }

      CompoundRuntimeException.throwIfNotEmpty(throwables);
    }
  }
}
