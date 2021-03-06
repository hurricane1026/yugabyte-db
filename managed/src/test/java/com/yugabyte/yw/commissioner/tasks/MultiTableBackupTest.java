// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.MultiTableBackup;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.ShellProcessHandler;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;
import com.google.protobuf.ByteString;

import org.yb.client.GetTableSchemaResponse;
import org.yb.client.ListTablesResponse;
import org.yb.client.YBClient;
import org.yb.master.Master;
import org.yb.master.Master.ListTablesResponsePB.TableInfo;
import org.yb.Common.TableType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.yugabyte.yw.models.Backup.BackupState.Completed;
import static com.yugabyte.yw.models.Backup.BackupState.Failed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class MultiTableBackupTest extends CommissionerBaseTest {

  @InjectMocks
  Commissioner commissioner;

  Universe defaultUniverse;
  YBClient mockClient;
  ListTablesResponse mockListTablesResponse;
  ListTablesResponse mockListTablesResponse1;
  GetTableSchemaResponse mockSchemaResponse1;
  GetTableSchemaResponse mockSchemaResponse2;
  GetTableSchemaResponse mockSchemaResponse3;
  UUID table1UUID = UUID.randomUUID();
  UUID table2UUID = UUID.randomUUID();
  UUID table3UUID = UUID.randomUUID();

  @Before
  public void setUp() {
    defaultCustomer = ModelFactory.testCustomer();
    defaultUniverse = ModelFactory.createUniverse();
    List<TableInfo> tableInfoList = new ArrayList<TableInfo>();
    List<TableInfo> tableInfoList1 = new ArrayList<TableInfo>();
    TableInfo ti1 = TableInfo.newBuilder()
        .setName("Table1")
        .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default0"))
        .setId(ByteString.copyFromUtf8(table1UUID.toString()))
        .setTableType(TableType.REDIS_TABLE_TYPE)
        .build();
    TableInfo ti2 = TableInfo.newBuilder()
        .setName("Table2")
        .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default1"))
        .setId(ByteString.copyFromUtf8(table2UUID.toString()))
        .setTableType(TableType.YQL_TABLE_TYPE)
        .build();
    TableInfo ti3 = TableInfo.newBuilder()
        .setName("Table3")
        .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default2"))
        .setId(ByteString.copyFromUtf8(table3UUID.toString()))
        .setTableType(TableType.PGSQL_TABLE_TYPE)
        .build();
    tableInfoList.add(ti1);
    tableInfoList.add(ti2);
    tableInfoList.add(ti3);
    tableInfoList1.add(ti1);
    mockClient = mock(YBClient.class);
    mockListTablesResponse = mock(ListTablesResponse.class);
    mockListTablesResponse1 = mock(ListTablesResponse.class);
    mockSchemaResponse1 = mock(GetTableSchemaResponse.class);
    mockSchemaResponse2 = mock(GetTableSchemaResponse.class);
    mockSchemaResponse3 = mock(GetTableSchemaResponse.class);
    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
    try {
      when(mockClient.getTablesList(null, true, null)).thenReturn(mockListTablesResponse);
      when(mockClient.getTablesList(null, true, "$$$Default0")).thenReturn(mockListTablesResponse1);
      when(mockClient.getTableSchemaByUUID(table1UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse1);
      when(mockClient.getTableSchemaByUUID(table2UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse2);
      when(mockClient.getTableSchemaByUUID(table3UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse3);
    } catch (Exception e) {
      // Do nothing.
    }
    when(mockListTablesResponse.getTableInfoList()).thenReturn(tableInfoList);
    when(mockListTablesResponse1.getTableInfoList()).thenReturn(tableInfoList1);
    when(mockSchemaResponse1.getTableName()).thenReturn("Table1");
    when(mockSchemaResponse1.getNamespace()).thenReturn("$$$Default0");
    when(mockSchemaResponse1.getTableType()).thenReturn(TableType.REDIS_TABLE_TYPE);
    when(mockSchemaResponse2.getTableName()).thenReturn("Table2");
    when(mockSchemaResponse2.getNamespace()).thenReturn("$$$Default1");
    when(mockSchemaResponse2.getTableType()).thenReturn(TableType.YQL_TABLE_TYPE);
    when(mockSchemaResponse3.getTableName()).thenReturn("Table3");
    when(mockSchemaResponse3.getNamespace()).thenReturn("$$$Default2");
    when(mockSchemaResponse3.getTableType()).thenReturn(TableType.PGSQL_TABLE_TYPE);
  }

  private TaskInfo submitTask(String keyspace, List<UUID> tableUUIDs) {
    MultiTableBackup.Params backupTableParams = new MultiTableBackup.Params();
    backupTableParams.universeUUID = defaultUniverse.universeUUID;
    backupTableParams.customerUUID = defaultCustomer.uuid;
    backupTableParams.keyspace = keyspace;
    backupTableParams.storageConfigUUID = UUID.randomUUID();
    backupTableParams.tableUUIDList = tableUUIDs;
    try {
      UUID taskUUID = commissioner.submit(TaskType.MultiTableBackup, backupTableParams);
      CustomerTask.create(defaultCustomer, defaultUniverse.universeUUID, taskUUID,
          CustomerTask.TargetType.Universe, CustomerTask.TaskType.Backup,
          "bar");
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  @Test
  public void testMultiTableBackup() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.setConfig(config);
    ShellProcessHandler.ShellResponse shellResponse =  new ShellProcessHandler.ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);

    TaskInfo taskInfo = submitTask(null, new ArrayList<UUID>());
    verify(mockTableManager, times(2)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupKeyspace() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.setConfig(config);
    ShellProcessHandler.ShellResponse shellResponse =  new ShellProcessHandler.ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);

    TaskInfo taskInfo = submitTask("$$$Default0", new ArrayList<UUID>());
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupList() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.setConfig(config);
    ShellProcessHandler.ShellResponse shellResponse =  new ShellProcessHandler.ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);
    List<UUID> tableUUIDs = new ArrayList<UUID>();
    tableUUIDs.add(table1UUID);
    tableUUIDs.add(table2UUID);
    tableUUIDs.add(table3UUID);
    TaskInfo taskInfo = submitTask(null, tableUUIDs);
    verify(mockTableManager, times(2)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupIgnore() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "false");
    defaultUniverse.setConfig(config);
    TaskInfo taskInfo = submitTask(null, new ArrayList<UUID>());
    verify(mockTableManager, times(0)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }
}
