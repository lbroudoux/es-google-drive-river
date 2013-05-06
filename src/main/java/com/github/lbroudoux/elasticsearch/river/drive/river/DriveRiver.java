/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.lbroudoux.elasticsearch.river.drive.river;

import java.util.Map;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import com.github.lbroudoux.elasticsearch.river.drive.connector.DriveConnector;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
/**
 * 
 * @author laurent
 */
public class DriveRiver extends AbstractRiverComponent implements River{

   private final Client client;
   
   private final String indexName;

   private final String typeName;

   private final long bulkSize;
   
   private volatile Thread feedThread;

   private volatile boolean closed = false;
   
   private final DriveRiverFeedDefinition feedDefinition;
   
   private final DriveConnector drive;
   
   @Inject
   protected DriveRiver(RiverName riverName, RiverSettings settings, Client client){
      super(riverName, settings);
      this.client = client;
      
      // Deal with connector settings.
      if (settings.settings().containsKey("google-drive")){
         Map<String, Object> feed = (Map<String, Object>)settings.settings().get("google-drive");
         
         // Retrieve feed settings.
         String feedname = XContentMapValues.nodeStringValue(feed.get("name"), null);
         String folder = XContentMapValues.nodeStringValue(feed.get("folder"), null);
         int updateRate = XContentMapValues.nodeIntegerValue(feed.get("update_rate"), 15 * 60 * 1000);
         
         // Retrieve connection settings.
         String clientId = XContentMapValues.nodeStringValue(feed.get("clientId"), null);
         String clientSecret = XContentMapValues.nodeStringValue(feed.get("clientSecret"), null);
         String refreshToken = XContentMapValues.nodeStringValue(feed.get("refreshToken"), null);
         
         feedDefinition = new DriveRiverFeedDefinition(feedname, folder, updateRate, clientId, clientSecret, refreshToken);
      } else {
         logger.error("You didn't define the google-drive settings. Exiting... See https://github.com/lbroudoux/es-google-drive-river");
         indexName = null;
         typeName = null;
         bulkSize = 100;
         feedDefinition = null;
         drive = null;
         return;
      }
      
      // Deal with index settings if provided.
      if (settings.settings().containsKey("index")) {
         Map<String, Object> indexSettings = (Map<String, Object>)settings.settings().get("index");
         
         indexName = XContentMapValues.nodeStringValue(
               indexSettings.get("index"), riverName.name());
         typeName = XContentMapValues.nodeStringValue(
               indexSettings.get("type"), DriveRiverUtil.INDEX_TYPE_DOC);
         bulkSize = XContentMapValues.nodeLongValue(
               indexSettings.get("bulk_size"), 100);
      } else {
         indexName = riverName.name();
         typeName = DriveRiverUtil.INDEX_TYPE_DOC;
         bulkSize = 100;
      }
      
      // We need to connect to Google Drive.
      drive = new DriveConnector(feedDefinition.getClientId(), feedDefinition.getClientSecret(), feedDefinition.getRefreshToken());
      drive.connectUserDrive();
   }

   @Override
   public void start(){
      if (logger.isInfoEnabled()){
         logger.info("Starting google drive river scanning");
      }
      try{
         client.admin().indices().prepareCreate(indexName).execute().actionGet();
      } catch (Exception e) {
         if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException){
            // that's fine.
         } else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException){
            // ok, not recovered yet..., lets start indexing and hope we recover by the first bulk.
         } else {
            logger.warn("failed to create index [{}], disabling river...", e, indexName);
            return;
         }
      }
      
      // We create as many Threads as there are feeds.
      feedThread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "fs_slurper")
            .newThread(new DriveScanner(feedDefinition));
      feedThread.start();
   }

   @Override
   public void close(){
      if (logger.isInfoEnabled()){
         logger.info("Closing google drive river");
      }
      closed = true;

      // We have to close the Thread.
      if (feedThread != null){
         feedThread.interrupt();
      }
   }
   
   /** */
   private class DriveScanner implements Runnable{
   
      private BulkRequestBuilder bulk;
      private DriveRiverFeedDefinition feedDefinition;
      
      public DriveScanner(DriveRiverFeedDefinition feedDefinition){
         this.feedDefinition = feedDefinition;
      }
      
      @Override
      public void run(){
         while (true){
            if (closed){
               return;
            }
            
            try{
               bulk = client.prepareBulk();
               
               Long lastChangesId = getLastChangesIdFromRiver("_lastChangesId");
               lastChangesId = scan(feedDefinition.getFolder(), lastChangesId);
               updateRiver("_lastChangesId", lastChangesId);
               
               // If some bulkActions remains, we should commit them
               commitBulk();
               
            } catch (Exception e){
               logger.warn("Error while indexing content from {}", feedDefinition.getFolder());
               if (logger.isDebugEnabled()){
                  logger.debug("Exception for folder {} is {}", feedDefinition.getFolder(), e);
                  e.printStackTrace();
               }
            }
            
            try {
               if (logger.isDebugEnabled()){
                  logger.debug("Google drive river is going to sleep for {} ms", feedDefinition.getUpdateRate());
               }
               Thread.sleep(feedDefinition.getUpdateRate());
            } catch (InterruptedException ie){
            }
         }
      }

      @SuppressWarnings("unchecked")
      private Long getLastChangesIdFromRiver(String lastChangesField){
         Long result = null;
         try {
            // Do something.
            client.admin().indices().prepareRefresh("_river").execute().actionGet();
            GetResponse lastSeqGetResponse = client.prepareGet("_river", riverName().name(),
                  lastChangesField).execute().actionGet();
            if (lastSeqGetResponse.isExists()) {
               Map<String, Object> fsState = (Map<String, Object>) lastSeqGetResponse.getSourceAsMap().get("google-drive");

               if (fsState != null) {
                  Object lastChanges = fsState.get("lastChangesField");
                  if (lastChanges != null) {
                     try{
                        result = Long.parseLong(lastChanges.toString());
                     } catch (NumberFormatException nfe){
                        logger.warn("Last recorded lastChangesId is not a Long {}", lastChanges.toString());
                     }
                  }
               }
            } else {
               // This is first call, just log in debug mode.
               if (logger.isDebugEnabled()){
                  logger.debug("{} doesn't exist", lastChangesField);
               }
            }
         } catch (Exception e) {
            logger.warn("failed to get _lastChangesId, throttling....", e);
         }

         if (logger.isDebugEnabled()){
            logger.debug("lastChangesId: {}", result);
         }
         return result;
      }
      
      private Long scan(String folder, Long lastChangesId) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Starting scanning of folder {} since {}", folder, lastChangesId);
         }
         drive.getChanges(folder, lastChangesId);
         return null;
      }

      private void updateRiver(String lastChangesField, Long lastChangesId) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("updating lastChangesField: {}", lastChangesId);
         }

         // We store the lastupdate date and some stats
         XContentBuilder xb = jsonBuilder()
            .startObject()
               .startObject("google-drive")
                  .field("feedname", feedDefinition.getFeedname())
                  .field(lastChangesField, lastChangesId)
               .endObject()
            .endObject();
         esIndex("_river", riverName.name(), lastChangesField, xb);
      }
      
      /**
       * Commit to ES if something is in queue
       * @throws Exception
       */
      private void commitBulk() throws Exception{
         if (bulk != null && bulk.numberOfActions() > 0){
            if (logger.isDebugEnabled()){
               logger.debug("ES Bulk Commit is needed");
            }
            BulkResponse response = bulk.execute().actionGet();
            if (response.hasFailures()){
               logger.warn("Failed to execute " + response.buildFailureMessage());
            }
         }
      }
      
      /**
       * Commit to ES if we have too much in bulk 
       * @throws Exception
       */
      private void commitBulkIfNeeded() throws Exception {
         if (bulk != null && bulk.numberOfActions() > 0 && bulk.numberOfActions() >= bulkSize){
            if (logger.isDebugEnabled()){
               logger.debug("ES Bulk Commit is needed");
            }
            
            BulkResponse response = bulk.execute().actionGet();
            if (response.hasFailures()){
               logger.warn("Failed to execute " + response.buildFailureMessage());
            }
            
            // Reinit a new bulk.
            bulk = client.prepareBulk();
         }
      }
      
      /**
       * Add to bulk an IndexRequest.
       */
      private void esIndex(String index, String type, String id, XContentBuilder xb) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Indexing in ES " + index + ", " + type + ", " + id);
         }
         if (logger.isTraceEnabled()){
            logger.trace("Json indexed : {}", xb.string());
         }
         
         bulk.add(client.prepareIndex(index, type, id).setSource(xb));
         commitBulkIfNeeded();
      }

      /**
       * Add to bulk a DeleteRequest.
       */
      private void esDelete(String index, String type, String id) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Deleting from ES " + index + ", " + type + ", " + id);
         }
         bulk.add(client.prepareDelete(index, type, id));
         commitBulkIfNeeded();
      }
   }
}
