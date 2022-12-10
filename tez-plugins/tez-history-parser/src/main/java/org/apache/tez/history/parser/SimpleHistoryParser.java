/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tez.history.parser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.tez.common.ATSConstants;
import org.apache.tez.common.Preconditions;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.history.logging.impl.SimpleHistoryLoggingService;
import org.apache.tez.dag.records.TezDAGID;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;
import org.apache.tez.dag.records.TezVertexID;
import org.apache.tez.history.parser.datamodel.BaseParser;
import org.apache.tez.history.parser.datamodel.Constants;
import org.apache.tez.history.parser.datamodel.DagInfo;
import org.apache.tez.history.parser.datamodel.TaskAttemptInfo;
import org.apache.tez.history.parser.datamodel.TaskInfo;
import org.apache.tez.history.parser.datamodel.VertexInfo;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;

/**
 * Parser utility to parse data generated by SimpleHistoryLogging to in-memory datamodel provided in
 * org.apache.tez.history.parser.datamodel.
 * <p/>
 * <p/>
 * Most of the information should be available. Minor info like VersionInfo may not be available, as
 * it is not captured in SimpleHistoryLogging.
 */
public class SimpleHistoryParser extends BaseParser {
  private static final Logger LOG = LoggerFactory.getLogger(SimpleHistoryParser.class);
  protected static final String UTF8 = "UTF-8";
  private final File historyFile;

  public SimpleHistoryParser(List<File> files) {
    super();
    Preconditions.checkArgument(checkFiles(files), files + " are empty or they don't exist");
    this.historyFile = files.get(0); //doesn't support multiple files at the moment
  }

  protected interface JSONObjectSource {
    boolean hasNext() throws IOException;
    JSONObject next() throws JSONException;
    void close();
  };

  /**
   * Get in-memory representation of DagInfo
   *
   * @return DagInfo
   * @throws TezException
   */
  public DagInfo getDAGData(String dagId) throws TezException {
    try {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(dagId), "Please provide valid dagId");
      dagId = dagId.trim();
      parseContents(historyFile, dagId);
      linkParsedContents();
      addRawDataToDagInfo();
      return dagInfo;
    } catch (IOException | JSONException e) {
      LOG.error("Error in reading DAG ", e);
      throw new TezException(e);
    }
  }

  private void populateOtherInfo(JSONObject source, JSONObject destination) throws JSONException {
    if (source == null || destination == null) {
      return;
    }
    for (Iterator it = source.keys(); it.hasNext(); ) {
      String key = (String) it.next();
      Object val = source.get(key);
      destination.put(key, val);
    }
  }

  private void populateOtherInfo(JSONObject source, String entityName,
      Map<String, JSONObject> destMap) throws JSONException {
    JSONObject destinationJson = destMap.get(entityName);
    JSONObject destOtherInfo = destinationJson.getJSONObject(Constants.OTHER_INFO);
    populateOtherInfo(source, destOtherInfo);
  }

  private void parseContents(File historyFile, String dagId)
      throws JSONException, FileNotFoundException, TezException, IOException {
    JSONObjectSource source = getJsonSource();

    parse(dagId, source);
  }

  private JSONObjectSource getJsonSource() throws FileNotFoundException {
    final Scanner scanner = new Scanner(historyFile, UTF8);
    scanner.useDelimiter(SimpleHistoryLoggingService.RECORD_SEPARATOR);

    JSONObjectSource source = new JSONObjectSource() {
      @Override
      public JSONObject next() throws JSONException {
        String line = scanner.next();
        return new JSONObject(line);
      }

      @Override
      public boolean hasNext() throws IOException {
        return scanner.hasNext();
      }

      @Override
      public void close() {
        scanner.close();
      }
    };
    return source;
  }

  protected void parse(String dagId, JSONObjectSource source)
      throws JSONException, TezException, IOException {
    Map<String, JSONObject> vertexJsonMap = Maps.newHashMap();
    Map<String, JSONObject> taskJsonMap = Maps.newHashMap();
    Map<String, JSONObject> attemptJsonMap = Maps.newHashMap();

    readEventsFromSource(dagId, source, vertexJsonMap, taskJsonMap, attemptJsonMap);
    postProcessMaps(vertexJsonMap, taskJsonMap, attemptJsonMap);
  }

  protected void postProcessMaps(Map<String, JSONObject> vertexJsonMap,
      Map<String, JSONObject> taskJsonMap, Map<String, JSONObject> attemptJsonMap)
      throws JSONException {
    for (JSONObject jsonObject : vertexJsonMap.values()) {
      VertexInfo vertexInfo = VertexInfo.create(jsonObject);
      this.vertexList.add(vertexInfo);
      LOG.debug("Parsed vertex {}", vertexInfo.getVertexName());
    }
    for (JSONObject jsonObject : taskJsonMap.values()) {
      TaskInfo taskInfo = TaskInfo.create(jsonObject);
      this.taskList.add(taskInfo);
      LOG.debug("Parsed task {}", taskInfo.getTaskId());
    }
    for (JSONObject jsonObject : attemptJsonMap.values()) {
      /**
       * For converting SimpleHistoryLogging to in-memory representation
       *
       * We need to get "relatedEntities":[{"entity":"cn055-10.l42scl.hortonworks.com:58690",
       * "entitytype":"nodeId"},{"entity":"container_1438652049951_0008_01_000152",
       * "entitytype":"containerId"} and populate it in otherInfo object so that in-memory
       * representation can parse it correctly
       */
      JSONArray relatedEntities = jsonObject.optJSONArray(Constants.RELATED_ENTITIES);
      if (relatedEntities == null) {
        //This can happen when CONTAINER_EXITED abruptly. (e.g Container failed, exitCode=1)
        LOG.debug("entity {} did not have related entities",
            jsonObject.optJSONObject(Constants.ENTITY));
      } else {
        JSONObject subJsonObject = relatedEntities.optJSONObject(0);
        if (subJsonObject != null) {
          String nodeId = subJsonObject.optString(Constants.ENTITY_TYPE);
          if (!Strings.isNullOrEmpty(nodeId) && nodeId.equalsIgnoreCase(Constants.NODE_ID)) {
            //populate it in otherInfo
            JSONObject otherInfo = jsonObject.optJSONObject(Constants.OTHER_INFO);
            String nodeIdVal = subJsonObject.optString(Constants.ENTITY);
            if (otherInfo != null && nodeIdVal != null) {
              otherInfo.put(Constants.NODE_ID, nodeIdVal);
            }
          }
        }

        subJsonObject = relatedEntities.optJSONObject(1);
        if (subJsonObject != null) {
          String containerId = subJsonObject.optString(Constants.ENTITY_TYPE);
          if (!Strings.isNullOrEmpty(containerId) && containerId
              .equalsIgnoreCase(Constants.CONTAINER_ID)) {
            //populate it in otherInfo
            JSONObject otherInfo = jsonObject.optJSONObject(Constants.OTHER_INFO);
            String containerIdVal = subJsonObject.optString(Constants.ENTITY);
            if (otherInfo != null && containerIdVal != null) {
              otherInfo.put(Constants.CONTAINER_ID, containerIdVal);
            }
          }
        }
      }
      TaskAttemptInfo attemptInfo = TaskAttemptInfo.create(jsonObject);
      this.attemptList.add(attemptInfo);
      LOG.debug("Parsed task attempt {}", attemptInfo.getTaskAttemptId());
    }
  }

  protected void readEventsFromSource(String dagId, JSONObjectSource source,
      Map<String, JSONObject> vertexJsonMap, Map<String, JSONObject> taskJsonMap,
      Map<String, JSONObject> attemptJsonMap) throws JSONException, TezException, IOException{
    JSONObject dagJson = null;
    TezDAGID tezDAGID = TezDAGID.fromString(dagId);
    String userName = null;

    while (source.hasNext()) {
      JSONObject jsonObject = source.next();

      String entity = jsonObject.getString(Constants.ENTITY);
      String entityType = jsonObject.getString(Constants.ENTITY_TYPE);
      switch (entityType) {
      case Constants.TEZ_DAG_ID:
        if (!dagId.equals(entity)) {
          LOG.warn(dagId + " is not matching with " + entity);
          continue;
        }
        // Club all DAG related information together (DAG_INIT, DAG_FINISH etc). Each of them
        // would have a set of entities in otherinfo (e.g vertex mapping, dagPlan, start/finish
        // time etc).
        if (dagJson == null) {
          dagJson = jsonObject;
        } else {
          if (dagJson.optJSONObject(ATSConstants.OTHER_INFO).optJSONObject(ATSConstants.DAG_PLAN) == null) {
            // if DAG_PLAN is not filled already, let's try to fetch it from other
            dagJson.getJSONObject(ATSConstants.OTHER_INFO).put(ATSConstants.DAG_PLAN,
                jsonObject.getJSONObject(ATSConstants.OTHER_INFO).getJSONObject(ATSConstants.DAG_PLAN));
          }
          mergeSubJSONArray(jsonObject, dagJson, Constants.EVENTS);
        }
        JSONArray relatedEntities = dagJson.optJSONArray(Constants
            .RELATED_ENTITIES);
        //UserName is present in related entities
        // {"entity":"userXYZ","entitytype":"user"}
        if (relatedEntities != null) {
          for (int i = 0; i < relatedEntities.length(); i++) {
            JSONObject subEntity = relatedEntities.getJSONObject(i);
            String subEntityType = subEntity.optString(Constants.ENTITY_TYPE);
            if (subEntityType != null && subEntityType.equals(Constants.USER)) {
              userName = subEntity.getString(Constants.ENTITY);
              break;
            }
          }
        }
        populateOtherInfo(jsonObject.optJSONObject(Constants.OTHER_INFO),
            dagJson.getJSONObject(Constants.OTHER_INFO));
        break;
      case Constants.TEZ_VERTEX_ID:
        String vertexName = entity;
        TezVertexID tezVertexID = TezVertexID.fromString(vertexName);
        if (!tezDAGID.equals(tezVertexID.getDAGID())) {
          LOG.warn("{} does not belong to {} ('{}' != '{}')}", vertexName, tezDAGID, tezDAGID, tezVertexID.getDAGID());
          continue;
        }
        if (!vertexJsonMap.containsKey(vertexName)) {
          vertexJsonMap.put(vertexName, jsonObject);
        } else {
          mergeSubJSONArray(jsonObject, vertexJsonMap.get(vertexName), Constants.EVENTS);
        }
        populateOtherInfo(jsonObject.optJSONObject(Constants.OTHER_INFO), vertexName, vertexJsonMap);
        break;
      case Constants.TEZ_TASK_ID:
        String taskName = entity;
        TezTaskID tezTaskID = TezTaskID.fromString(taskName);
        if (!tezDAGID.equals(tezTaskID.getDAGID())) {
          LOG.warn("{} does not belong to {} ('{}' != '{}')}", taskName, tezDAGID, tezDAGID,
              tezTaskID.getDAGID());
          continue;
        }
        if (!taskJsonMap.containsKey(taskName)) {
          taskJsonMap.put(taskName, jsonObject);
        } else {
          mergeSubJSONArray(jsonObject, taskJsonMap.get(taskName), Constants.EVENTS);
        }
        populateOtherInfo(jsonObject.optJSONObject(Constants.OTHER_INFO), taskName, taskJsonMap);
        break;
      case Constants.TEZ_TASK_ATTEMPT_ID:
        String taskAttemptName = entity;
        TezTaskAttemptID tezAttemptId = TezTaskAttemptID.fromString(taskAttemptName);
        if (!tezDAGID.equals(tezAttemptId.getDAGID())) {
          LOG.warn("{} does not belong to {} ('{}' != '{}')}", taskAttemptName, tezDAGID, tezDAGID,
              tezAttemptId.getDAGID());
          continue;
        }
        if (!attemptJsonMap.containsKey(taskAttemptName)) {
          attemptJsonMap.put(taskAttemptName, jsonObject);
        } else {
          mergeSubJSONArray(jsonObject, attemptJsonMap.get(taskAttemptName), Constants.EVENTS);
        }
        populateOtherInfo(jsonObject.optJSONObject(Constants.OTHER_INFO), taskAttemptName, attemptJsonMap);
        break;
      default:
        break;
      }
    }
    source.close();
    if (dagJson != null) {
      this.dagInfo = DagInfo.create(dagJson);
      setUserName(userName);
    } else {
      LOG.error("Dag is not yet parsed. Looks like partial file.");
      throw new TezException(
          "Please provide a valid/complete history log file containing " + dagId);
    }
  }

  private void mergeSubJSONArray(JSONObject source, JSONObject destination, String key)
      throws JSONException {
    if (source.optJSONArray(key) == null) {
      source.put(key, new JSONArray());
    }
    if (destination.optJSONArray(key) == null) {
      destination.put(key, new JSONArray());
    }
    for (int i = 0; i < source.getJSONArray(key).length(); i++) {
      destination.getJSONArray(key).put(source.getJSONArray(key).get(i));
    }
  }
}