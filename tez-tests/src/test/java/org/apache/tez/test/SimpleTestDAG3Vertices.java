/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.tez.common.TezUtils;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.EdgeProperty.DataMovementType;
import org.apache.tez.dag.api.EdgeProperty.DataSourceType;
import org.apache.tez.dag.api.EdgeProperty.SchedulingType;

/**
 * Simple Test DAG with 3 vertices using TestProcessor/TestInput/TestOutput.
 * 
 * v1
 * |
 * v2
 * |
 * v3
 *
 */
public final class SimpleTestDAG3Vertices {
  static Resource defaultResource = Resource.newInstance(100, 0);
  public static String TEZ_SIMPLE_DAG_NUM_TASKS =
      "tez.simple-test-dag-3-vertices.num-tasks";
  public static int TEZ_SIMPLE_DAG_NUM_TASKS_DEFAULT = 2;

  private SimpleTestDAG3Vertices() {}
  
  public static DAG createDAG(String name, 
      Configuration conf) throws Exception {
    UserPayload payload = null;
    int taskCount = TEZ_SIMPLE_DAG_NUM_TASKS_DEFAULT;
    if (conf != null) {
      taskCount = conf.getInt(TEZ_SIMPLE_DAG_NUM_TASKS, TEZ_SIMPLE_DAG_NUM_TASKS_DEFAULT);
      payload = TezUtils.createUserPayloadFromConf(conf);
    }
    DAG dag = DAG.create(name);
    Vertex v1 = Vertex.create("v1", TestProcessor.getProcDesc(payload), taskCount, defaultResource);
    Vertex v2 = Vertex.create("v2", TestProcessor.getProcDesc(payload), taskCount, defaultResource);
    Vertex v3 = Vertex.create("v3", TestProcessor.getProcDesc(payload), taskCount, defaultResource);
    dag.addVertex(v1).addVertex(v2).addEdge(Edge.create(v1, v2,
        EdgeProperty.create(DataMovementType.SCATTER_GATHER,
            DataSourceType.PERSISTED,
            SchedulingType.SEQUENTIAL,
            TestOutput.getOutputDesc(payload),
            TestInput.getInputDesc(payload))));
    dag.addVertex(v3).addEdge(Edge.create(v2, v3,
        EdgeProperty.create(DataMovementType.SCATTER_GATHER,
            DataSourceType.PERSISTED,
            SchedulingType.SEQUENTIAL,
            TestOutput.getOutputDesc(payload),
            TestInput.getInputDesc(payload))));
    return dag;
  }
  
  public static DAG createDAG(Configuration conf) throws Exception {
    return createDAG("SimpleTestDAG3Vertices", conf);
  }
}
