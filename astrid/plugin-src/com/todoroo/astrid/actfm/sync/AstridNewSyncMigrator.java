package com.todoroo.astrid.actfm.sync;

import java.util.HashSet;
import java.util.Set;

import android.text.TextUtils;
import android.util.Log;

import com.todoroo.andlib.data.DatabaseDao;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Functions;
import com.todoroo.andlib.sql.Order;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.sync.messages.NameMaps;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.dao.OutstandingEntryDao;
import com.todoroo.astrid.dao.RemoteModelDao;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskAttachmentDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskListMetadataDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.dao.UpdateDao;
import com.todoroo.astrid.dao.UserActivityDao;
import com.todoroo.astrid.dao.UserDao;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.OutstandingEntry;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.SyncFlags;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskAttachment;
import com.todoroo.astrid.data.TaskListMetadata;
import com.todoroo.astrid.data.TaskOutstanding;
import com.todoroo.astrid.data.Update;
import com.todoroo.astrid.data.User;
import com.todoroo.astrid.data.UserActivity;
import com.todoroo.astrid.files.FileMetadata;
import com.todoroo.astrid.helper.UUIDHelper;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.TagDataService;
import com.todoroo.astrid.subtasks.SubtasksHelper;
import com.todoroo.astrid.subtasks.SubtasksUpdater;
import com.todoroo.astrid.tags.TaskToTagMetadata;

@SuppressWarnings("nls")
public class AstridNewSyncMigrator {

    @Autowired private MetadataService metadataService;
    @Autowired private TagDataService tagDataService;
    @Autowired private TagDataDao tagDataDao;
    @Autowired private TaskDao taskDao;
    @Autowired private UpdateDao updateDao;
    @Autowired private UserActivityDao userActivityDao;
    @Autowired private UserDao userDao;
    @Autowired private TaskAttachmentDao taskAttachmentDao;
    @Autowired private TaskListMetadataDao taskListMetadataDao;

    @Autowired private TaskOutstandingDao taskOutstandingDao;
    @Autowired private TagOutstandingDao tagOutstandingDao;

    private static final String LOG_TAG = "sync-migrate";

    public static final String PREF_SYNC_MIGRATION = "p_sync_migration";

    public AstridNewSyncMigrator() {
        DependencyInjectionService.getInstance().inject(this);
    }

    @SuppressWarnings("deprecation")
    public void performMigration() {
        if (Preferences.getBoolean(PREF_SYNC_MIGRATION, false))
            return;

        // --------------
        // First ensure that a TagData object exists for each tag metadata
        // --------------
        try {
            Query noTagDataQuery = Query.select(Metadata.PROPERTIES).where(Criterion.and(
                    MetadataCriteria.withKey(TaskToTagMetadata.KEY),
                    Criterion.or(TaskToTagMetadata.TAG_UUID.isNull(), TaskToTagMetadata.TAG_UUID.eq(0)),
                    Criterion.not(TaskToTagMetadata.TAG_NAME.in(Query.select(TagData.NAME).from(TagData.TABLE))))).groupBy(TaskToTagMetadata.TAG_NAME);

            TodorooCursor<Metadata> noTagData = metadataService.query(noTagDataQuery);
            try {
                Metadata tag = new Metadata();
                for (noTagData.moveToFirst(); !noTagData.isAfterLast(); noTagData.moveToNext()) {
                    tag.readFromCursor(noTagData);

                    if (ActFmInvoker.SYNC_DEBUG)
                        Log.w(LOG_TAG, "CREATING TAG DATA " + tag.getValue(TaskToTagMetadata.TAG_NAME));

                    TagData newTagData = new TagData();
                    newTagData.setValue(TagData.NAME, tag.getValue(TaskToTagMetadata.TAG_NAME));
                    tagDataService.save(newTagData);
                }
            } finally {
                noTagData.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error creating tag data", e);
        }

        // --------------
        // Delete all emergent tag data, we don't need it
        // --------------
        try {
            TodorooCursor<TagData> emergentTags = tagDataDao.query(Query.select(TagData.ID, TagData.NAME).where(Functions.bitwiseAnd(TagData.FLAGS, TagData.FLAG_EMERGENT).gt(0)));
            try {
                TagData td = new TagData();
                for (emergentTags.moveToFirst(); !emergentTags.isAfterLast(); emergentTags.moveToNext()) {
                    td.clear();
                    td.readFromCursor(emergentTags);
                    String name = td.getValue(TagData.NAME);
                    tagDataDao.delete(td.getId());
                    if (!TextUtils.isEmpty(name))
                        metadataService.deleteWhere(Criterion.and(MetadataCriteria.withKey(TaskToTagMetadata.KEY), TaskToTagMetadata.TAG_NAME.eq(name)));
                }
            } finally {
                emergentTags.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error clearing emergent tags");
        }

        // --------------
        // Then ensure that every remote model has a remote id, by generating one using the uuid generator for all those without one
        // --------------
        final Set<Long> tasksThatNeedTagSync = new HashSet<Long>();
        try {
            Query tagsQuery = Query.select(TagData.ID, TagData.UUID, TagData.MODIFICATION_DATE).where(Criterion.or(TagData.UUID.eq(RemoteModel.NO_UUID), TagData.UUID.isNull()));
            assertUUIDsExist(tagsQuery, new TagData(), tagDataDao, tagOutstandingDao, new TagOutstanding(), NameMaps.syncableProperties(NameMaps.TABLE_ID_TAGS), new UUIDAssertionExtras<TagData>() {
                private static final String LAST_TAG_FETCH_TIME = "actfm_lastTag"; //$NON-NLS-1$
                private final long lastFetchTime = Preferences.getInt(LAST_TAG_FETCH_TIME, 0) * 1000L;

                @Override
                public void beforeSave(TagData instance) {/**/}

                @Override
                public boolean shouldCreateOutstandingEntries(TagData instance) {
                    boolean result = lastFetchTime == 0 || (instance.containsNonNullValue(TagData.MODIFICATION_DATE) && instance.getValue(TagData.MODIFICATION_DATE) > lastFetchTime);
                    return result && RemoteModelDao.getOutstandingEntryFlag();
                }

                @Override
                public void afterSave(TagData instance, boolean createdOutstanding) {/**/}
            });

            Query tasksQuery = Query.select(Task.ID, Task.UUID, Task.RECURRENCE, Task.FLAGS, Task.MODIFICATION_DATE, Task.LAST_SYNC).where(Criterion.all);
            assertUUIDsExist(tasksQuery, new Task(), taskDao, taskOutstandingDao, new TaskOutstanding(), NameMaps.syncableProperties(NameMaps.TABLE_ID_TASKS), new UUIDAssertionExtras<Task>() {
                @Override
                public void beforeSave(Task instance) {
                    if (instance.getFlag(Task.FLAGS, Task.FLAG_IS_READONLY)) {
                        instance.setFlag(Task.FLAGS, Task.FLAG_IS_READONLY, false);
                        instance.setValue(Task.IS_READONLY, 1);
                    }

                    if (instance.getFlag(Task.FLAGS, Task.FLAG_PUBLIC)) {
                        instance.setFlag(Task.FLAGS, Task.FLAG_PUBLIC, false);
                        instance.setValue(Task.IS_PUBLIC, 1);
                    }

                    String recurrence = instance.getValue(Task.RECURRENCE);
                    if (!TextUtils.isEmpty(recurrence)) {
                        boolean repeatAfterCompletion = instance.getFlag(Task.FLAGS, Task.FLAG_REPEAT_AFTER_COMPLETION);
                        instance.setFlag(Task.FLAGS, Task.FLAG_REPEAT_AFTER_COMPLETION, false);

                        recurrence = recurrence.replaceAll("BYDAY=;", "");
                        if (repeatAfterCompletion)
                            recurrence = recurrence + ";FROM=COMPLETION";
                        instance.setValue(Task.RECURRENCE, recurrence);
                    }
                }

                @Override
                public boolean shouldCreateOutstandingEntries(Task instance) {
                    if (!instance.containsNonNullValue(Task.MODIFICATION_DATE) || instance.getValue(Task.LAST_SYNC) == 0)
                        return RemoteModelDao.getOutstandingEntryFlag();

                    return (instance.getValue(Task.LAST_SYNC) < instance.getValue(Task.MODIFICATION_DATE)) && RemoteModelDao.getOutstandingEntryFlag();
                }

                @Override
                public void afterSave(Task instance, boolean createdOutstanding) {
                    if (createdOutstanding)
                        tasksThatNeedTagSync.add(instance.getId());
                }
            });
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error asserting UUIDs", e);
        }

        // --------------
        // Migrate unsynced task comments to UserActivity table
        // --------------
        try {
            TodorooCursor<Update> updates = updateDao.query(Query.select(Update.PROPERTIES).where(
                    Criterion.and(Criterion.or(Update.REMOTE_ID.eq(0), Update.REMOTE_ID.isNull()), Criterion.or(Update.ACTION_CODE.eq(UserActivity.ACTION_TAG_COMMENT),
                            Update.ACTION_CODE.eq(UserActivity.ACTION_TASK_COMMENT)))));
            try {
                Update update = new Update();
                UserActivity userActivity = new UserActivity();
                for (updates.moveToFirst(); !updates.isAfterLast(); updates.moveToNext()) {
                    update.clear();
                    userActivity.clear();

                    update.readFromCursor(updates);

                    boolean setTarget = true;
                    if (!RemoteModel.isUuidEmpty(update.getValue(Update.TASK).toString())) {
                        userActivity.setValue(UserActivity.TARGET_ID, update.getValue(Update.TASK).toString());
                    } else if (update.getValue(Update.TASK_LOCAL) > 0) {
                        Task local = taskDao.fetch(update.getValue(Update.TASK_LOCAL), Task.UUID);
                        if (local != null && !RemoteModel.isUuidEmpty(local.getUuid()))
                            userActivity.setValue(UserActivity.TARGET_ID, local.getUuid());
                        else
                            setTarget = false;
                    } else {
                        setTarget = false;
                    }

                    if (setTarget) {
                        userActivity.setValue(UserActivity.USER_UUID, update.getValue(Update.USER_ID).toString());
                        userActivity.setValue(UserActivity.ACTION, update.getValue(Update.ACTION_CODE));
                        userActivity.setValue(UserActivity.MESSAGE, update.getValue(Update.MESSAGE));
                        userActivity.setValue(UserActivity.CREATED_AT, update.getValue(Update.CREATION_DATE));
                        userActivityDao.createNew(userActivity);
                    }

                }
            } finally {
                updates.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error migrating updates", e);
        }


        // --------------
        // Drop any entries from the Users table that don't have a UUID
        // --------------
        try {
            userDao.deleteWhere(Criterion.or(User.UUID.isNull(), User.UUID.eq(""), User.UUID.eq("0")));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error deleting incomplete user entries", e);
        }

        // --------------
        // Migrate legacy FileMetadata models to new TaskAttachment models
        // --------------
        try {
            TodorooCursor<Metadata> fmCursor = metadataService.query(Query.select(Metadata.PROPERTIES)
                    .where(MetadataCriteria.withKey(FileMetadata.METADATA_KEY)));
            try {
                Metadata m = new Metadata();
                for (fmCursor.moveToFirst(); !fmCursor.isAfterLast(); fmCursor.moveToNext()) {
                    m.clear();
                    m.readFromCursor(fmCursor);

                    TaskAttachment attachment = new TaskAttachment();
                    Task task = taskDao.fetch(m.getValue(Metadata.TASK), Task.UUID);
                    if (task == null || !RemoteModel.isValidUuid(task.getUuid()))
                        continue;

                    Long oldRemoteId = m.getValue(FileMetadata.REMOTE_ID);
                    boolean synced = false;
                    if (oldRemoteId != null && oldRemoteId > 0) {
                        synced = true;
                        attachment.setValue(TaskAttachment.UUID, Long.toString(oldRemoteId));
                    }
                    attachment.setValue(TaskAttachment.TASK_UUID, task.getUuid());
                    if (m.containsNonNullValue(FileMetadata.NAME))
                        attachment.setValue(TaskAttachment.NAME, m.getValue(FileMetadata.NAME));
                    if (m.containsNonNullValue(FileMetadata.URL))
                        attachment.setValue(TaskAttachment.URL, m.getValue(FileMetadata.URL));
                    if (m.containsNonNullValue(FileMetadata.FILE_PATH))
                        attachment.setValue(TaskAttachment.FILE_PATH, m.getValue(FileMetadata.FILE_PATH));
                    if (m.containsNonNullValue(FileMetadata.FILE_TYPE))
                        attachment.setValue(TaskAttachment.CONTENT_TYPE, m.getValue(FileMetadata.FILE_TYPE));
                    if (m.containsNonNullValue(FileMetadata.DELETION_DATE))
                        attachment.setValue(TaskAttachment.DELETED_AT, m.getValue(FileMetadata.DELETION_DATE));

                    if (synced) {
                        attachment.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
                    }

                    taskAttachmentDao.createNew(attachment);

                }
            } finally {
                fmCursor.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error migrating task attachment metadata", e);
        }

        // --------------
        // Migrate legacy FileMetadata models to new TaskAttachment models
        // --------------
        try {
            String activeTasksOrder = Preferences.getStringValue(SubtasksUpdater.ACTIVE_TASKS_ORDER);
            if (TextUtils.isEmpty(activeTasksOrder))
                activeTasksOrder = "[]";

            activeTasksOrder = SubtasksHelper.convertTreeToRemoteIds(activeTasksOrder);

            TaskListMetadata tlm = new TaskListMetadata();
            tlm.setValue(TaskListMetadata.FILTER, TaskListMetadata.FILTER_ID_ALL);
            tlm.setValue(TaskListMetadata.TASK_IDS, activeTasksOrder);
            tlm.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
            if (taskListMetadataDao.update(TaskListMetadata.FILTER.eq(TaskListMetadata.FILTER_ID_ALL), tlm) <= 0) {
                tlm.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
                taskListMetadataDao.createNew(tlm);
            }

            tlm.clear();
            String todayTasksOrder = Preferences.getStringValue(SubtasksUpdater.TODAY_TASKS_ORDER);
            if (TextUtils.isEmpty(todayTasksOrder))
                todayTasksOrder = "[]";

            todayTasksOrder = SubtasksHelper.convertTreeToRemoteIds(todayTasksOrder);

            tlm.setValue(TaskListMetadata.FILTER, TaskListMetadata.FILTER_ID_TODAY);
            tlm.setValue(TaskListMetadata.TASK_IDS, todayTasksOrder);
            tlm.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
            if (taskListMetadataDao.update(TaskListMetadata.FILTER.eq(TaskListMetadata.FILTER_ID_TODAY), tlm) <= 0) {
                tlm.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
                taskListMetadataDao.createNew(tlm);
            }

            TodorooCursor<TagData> allTagData = tagDataDao.query(Query.select(TagData.ID, TagData.UUID, TagData.TAG_ORDERING));
            try {
                TagData td = new TagData();
                for (allTagData.moveToFirst(); !allTagData.isAfterLast(); allTagData.moveToNext()) {
                    tlm.clear();
                    td.clear();

                    td.readFromCursor(allTagData);
                    String tagOrdering = td.getValue(TagData.TAG_ORDERING);
                    tagOrdering = SubtasksHelper.convertTreeToRemoteIds(tagOrdering);

                    tlm.setValue(TaskListMetadata.TASK_IDS, tagOrdering);
                    tlm.setValue(TaskListMetadata.TAG_UUID, td.getUuid());
                    taskListMetadataDao.createNew(tlm);
                }
            } finally {
                allTagData.close();
            }

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error migrating tag ordering", e);
        }

        // --------------
        // Ensure that all tag metadata entities have all important fields filled in
        // --------------
        try {
            Query incompleteQuery = Query.select(Metadata.PROPERTIES).where(Criterion.and(
                    MetadataCriteria.withKey(TaskToTagMetadata.KEY),
                    Criterion.or(TaskToTagMetadata.TASK_UUID.eq(0), TaskToTagMetadata.TASK_UUID.isNull(),
                            TaskToTagMetadata.TAG_UUID.eq(0), TaskToTagMetadata.TAG_UUID.isNull())));
            TodorooCursor<Metadata> incompleteMetadata = metadataService.query(incompleteQuery);
            try {
                Metadata m = new Metadata();
                for (incompleteMetadata.moveToFirst(); !incompleteMetadata.isAfterLast(); incompleteMetadata.moveToNext()) {
                    m.clear(); // Need this since some properties may be null
                    m.readFromCursor(incompleteMetadata);
                    boolean changes = false;

                    if (ActFmInvoker.SYNC_DEBUG)
                        Log.w(LOG_TAG, "Incomplete linking task " + m.getValue(Metadata.TASK) + " to " + m.getValue(TaskToTagMetadata.TAG_NAME));

                    if (!m.containsNonNullValue(TaskToTagMetadata.TASK_UUID) || RemoteModel.isUuidEmpty(m.getValue(TaskToTagMetadata.TASK_UUID))) {
                        if (ActFmInvoker.SYNC_DEBUG)
                            Log.w(LOG_TAG, "No task uuid");
                        updateTaskUuid(m);
                        changes = true;
                    }

                    if (!m.containsNonNullValue(TaskToTagMetadata.TAG_UUID) || RemoteModel.isUuidEmpty(m.getValue(TaskToTagMetadata.TAG_UUID))) {
                        if (ActFmInvoker.SYNC_DEBUG)
                            Log.w(LOG_TAG, "No tag uuid");
                        updateTagUuid(m);
                        changes = true;
                    }

                    if (changes)
                        metadataService.save(m);

                }
            } finally {
                incompleteMetadata.close();
            }

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error validating task to tag metadata", e);
        }

        // --------------
        // Finally, create oustanding entries for tags on unsynced tasks
        // --------------
        try {
            Long[] ids = tasksThatNeedTagSync.toArray(new Long[tasksThatNeedTagSync.size()]);
            TodorooCursor<Metadata> tagsAdded = metadataService.query(Query.select(Metadata.PROPERTIES)
                    .where(Criterion.and(MetadataCriteria.withKey(TaskToTagMetadata.KEY), Metadata.TASK.in(ids))).orderBy(Order.asc(Metadata.TASK)));
            try {
                Metadata m = new Metadata();
                for (tagsAdded.moveToFirst(); !tagsAdded.isAfterLast(); tagsAdded.moveToNext()) {
                    m.clear();
                    m.readFromCursor(tagsAdded);
                    Long deletionDate = m.getValue(Metadata.DELETION_DATE);
                    String tagUuid = m.getValue(TaskToTagMetadata.TAG_UUID);
                    if (!RemoteModel.isValidUuid(tagUuid))
                        continue;

                    TaskOutstanding to = new TaskOutstanding();
                    to.setValue(OutstandingEntry.ENTITY_ID_PROPERTY, m.getValue(Metadata.TASK));
                    to.setValue(OutstandingEntry.CREATED_AT_PROPERTY, DateUtilities.now());
                    String addedOrRemoved = NameMaps.TAG_ADDED_COLUMN;
                    if (deletionDate != null && deletionDate > 0)
                        addedOrRemoved = NameMaps.TAG_REMOVED_COLUMN;

                    to.setValue(OutstandingEntry.COLUMN_STRING_PROPERTY, addedOrRemoved);
                    to.setValue(OutstandingEntry.VALUE_STRING_PROPERTY, tagUuid);
                    taskOutstandingDao.createNew(to);
                }
            } finally {
                tagsAdded.close();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error creating tag_added outstanding entries", e);
        }

        Preferences.setBoolean(PREF_SYNC_MIGRATION, true);
        ActFmSyncMonitor monitor = ActFmSyncMonitor.getInstance();
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private interface UUIDAssertionExtras<TYPE extends RemoteModel> {
        void beforeSave(TYPE instance);
        boolean shouldCreateOutstandingEntries(TYPE instance);
        void afterSave(TYPE instance, boolean createdOutstanding);
    }

    private <TYPE extends RemoteModel, OE extends OutstandingEntry<TYPE>> void assertUUIDsExist(Query query, TYPE instance, DatabaseDao<TYPE> dao, OutstandingEntryDao<OE> oeDao, OE oe, Property<?>[] propertiesForOutstanding, UUIDAssertionExtras<TYPE> extras) {
        TodorooCursor<TYPE> cursor = dao.query(query);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                instance.clear();
                instance.readPropertiesFromCursor(cursor);
                boolean unsyncedModel = false;
                if (!instance.containsNonNullValue(RemoteModel.UUID_PROPERTY) || RemoteModel.NO_UUID.equals(instance.getValue(RemoteModel.UUID_PROPERTY)) ||
                        "".equals(instance.getValue(RemoteModel.UUID_PROPERTY)) || "null".equals(instance.getValue(RemoteModel.UUID_PROPERTY))) {
                    // No remote id exists, just create a UUID
                    unsyncedModel = true;
                    instance.setValue(RemoteModel.UUID_PROPERTY, UUIDHelper.newUUID());
                }
                if (extras != null)
                    extras.beforeSave(instance);

                instance.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
                dao.saveExisting(instance);
                boolean createdOutstanding = false;
                if (propertiesForOutstanding != null && (unsyncedModel || (extras != null && extras.shouldCreateOutstandingEntries(instance)))) {
                    createdOutstanding = true;
                    createOutstandingEntries(instance.getId(), dao, oeDao, oe, propertiesForOutstanding);
                }
                if (extras != null)
                    extras.afterSave(instance, createdOutstanding);
            }
        } finally {
            cursor.close();
        }
    }

    private <TYPE extends RemoteModel, OE extends OutstandingEntry<TYPE>> void createOutstandingEntries(long id, DatabaseDao<TYPE> dao, OutstandingEntryDao<OE> oeDao, OE oe, Property<?>[] propertiesForOutstanding) {
        TYPE instance = dao.fetch(id, propertiesForOutstanding);
        long now = DateUtilities.now();
        for (Property<?> property : propertiesForOutstanding) {
            oe.clear();
            oe.setValue(OutstandingEntry.ENTITY_ID_PROPERTY, id);
            oe.setValue(OutstandingEntry.COLUMN_STRING_PROPERTY, property.name);
            oe.setValue(OutstandingEntry.VALUE_STRING_PROPERTY, instance.getValue(property).toString());
            oe.setValue(OutstandingEntry.CREATED_AT_PROPERTY, now);
            oeDao.createNew(oe);
        }
    }

    private void updateTaskUuid(Metadata m) {
        long taskId = m.getValue(Metadata.TASK);
        Task task = taskDao.fetch(taskId, Task.UUID);
        if (task != null) {
            if (ActFmInvoker.SYNC_DEBUG)
                Log.w(LOG_TAG, "Linking with task uuid " + task.getValue(Task.UUID));
            m.setValue(TaskToTagMetadata.TASK_UUID, task.getValue(Task.UUID));
        } else {
            if (ActFmInvoker.SYNC_DEBUG)
                Log.w(LOG_TAG, "Task not found, deleting link");
            m.setValue(Metadata.DELETION_DATE, DateUtilities.now());
        }
    }

    private void updateTagUuid(Metadata m) {
        String tag = m.getValue(TaskToTagMetadata.TAG_NAME);
        TagData tagData = tagDataService.getTagByName(tag, TagData.UUID);
        if (tagData != null) {
            if (ActFmInvoker.SYNC_DEBUG)
                Log.w(LOG_TAG, "Linking with tag uuid " + tagData.getValue(TagData.UUID));
            m.setValue(TaskToTagMetadata.TAG_UUID, tagData.getValue(TagData.UUID));
        } else {
            if (ActFmInvoker.SYNC_DEBUG)
                Log.w(LOG_TAG, "Tag not found, deleting link");
            m.setValue(Metadata.DELETION_DATE, DateUtilities.now());
        }
    }

}
