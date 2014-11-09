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

import java.util.Arrays;
import java.util.Map;

import org.apache.tika.metadata.Metadata;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.bulk.*;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import com.github.lbroudoux.elasticsearch.river.drive.connector.DriveChanges;
import com.github.lbroudoux.elasticsearch.river.drive.connector.DriveConnector;
import com.github.lbroudoux.elasticsearch.river.drive.river.TikaHolder;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.File;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
/**
 * A River component for scanning and indexing Google Drive documents into Elasticsearch.
 * @author laurent
 */
public class DriveRiver extends AbstractRiverComponent implements River{
   
   private final Client client;
   
   private final String indexName;

   private final String typeName;

   private final int bulkSize;
   
   private volatile Thread feedThread;

   private volatile BulkProcessor bulkProcessor;

   private volatile boolean closed = false;
   
   private final DriveRiverFeedDefinition feedDefinition;
   
   private final DriveConnector drive;
   
   @Inject
   @SuppressWarnings({ "unchecked" })
   protected DriveRiver(RiverName riverName, RiverSettings settings, Client client) throws Exception{
      super(riverName, settings);
      this.client = client;
      
      // Deal with connector settings.
      if (settings.settings().containsKey("google-drive")){
         Map<String, Object> feed = (Map<String, Object>)settings.settings().get("google-drive");
         
         // Retrieve feed settings.
         String feedname = XContentMapValues.nodeStringValue(feed.get("name"), null);
         String folder = XContentMapValues.nodeStringValue(feed.get("folder"), null);
         int updateRate = XContentMapValues.nodeIntegerValue(feed.get("update_rate"), 15 * 60 * 1000);
         
         String[] includes = DriveRiverUtil.buildArrayFromSettings(settings.settings(), "google-drive.includes");
         String[] excludes = DriveRiverUtil.buildArrayFromSettings(settings.settings(), "google-drive.excludes");
         
         // Retrieve connection settings.
         String clientId = XContentMapValues.nodeStringValue(feed.get("clientId"), null);
         String clientSecret = XContentMapValues.nodeStringValue(feed.get("clientSecret"), null);
         String refreshToken = XContentMapValues.nodeStringValue(feed.get("refreshToken"), null);
         
         feedDefinition = new DriveRiverFeedDefinition(feedname, folder, updateRate, 
               Arrays.asList(includes), Arrays.asList(excludes), clientId, clientSecret, refreshToken);
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
         
         indexName = XContentMapValues.nodeStringValue(indexSettings.get("index"), riverName.name());
         typeName = XContentMapValues.nodeStringValue(indexSettings.get("type"), DriveRiverUtil.INDEX_TYPE_DOC);
         bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
      } else {
         indexName = riverName.name();
         typeName = DriveRiverUtil.INDEX_TYPE_DOC;
         bulkSize = 100;
      }
      
      // We need to connect to Google Drive.
      drive = new DriveConnector(feedDefinition.getClientId(), feedDefinition.getClientSecret(), feedDefinition.getRefreshToken());
      drive.connectUserDrive(feedDefinition.getFolder());
   }

   @Override
   public void start(){
      if (logger.isInfoEnabled()){
         logger.info("Starting google drive river scanning");
      }
      try{
         // Create the index if it doesn't exist.
         if (!client.admin().indices().prepareExists(indexName).execute().actionGet().isExists()){
            client.admin().indices().prepareCreate(indexName).execute().actionGet();
         }
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
      
      try{
         // If needed, we create the new mapping for files
         pushMapping(indexName, typeName, DriveRiverUtil.buildDriveFileMapping(typeName));       
      } catch (Exception e) {
         logger.warn("Failed to create mapping for [{}/{}], disabling river...",
               e, indexName, typeName);
         return;
      }

      // Creating bulk processor
      this.bulkProcessor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
         @Override
         public void beforeBulk(long id, BulkRequest request) {
            logger.debug("Going to execute new bulk composed of {} actions", request.numberOfActions());
         }

         @Override
         public void afterBulk(long id, BulkRequest request, BulkResponse response) {
            logger.debug("Executed bulk composed of {} actions", request.numberOfActions());
            if (response.hasFailures()) {
               logger.warn("There was failures while executing bulk", response.buildFailureMessage());
               if (logger.isDebugEnabled()) {
                  for (BulkItemResponse item : response.getItems()) {
                     if (item.isFailed()) {
                        logger.debug("Error for {}/{}/{} for {} operation: {}", item.getIndex(),
                              item.getType(), item.getId(), item.getOpType(), item.getFailureMessage());
                     }
                  }
               }
            }
         }

         @Override
         public void afterBulk(long id, BulkRequest request, Throwable throwable) {
            logger.warn("Error executing bulk", throwable);
         }
      })
            .setBulkActions(bulkSize)
            .build();

      // We create as many Threads as there are feeds.
      feedThread = EsExecutors.daemonThreadFactory(settings.globalSettings(),"fs_slurper")
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
   
   /**
    * Check if a mapping already exists in an index
    * @param index Index name
    * @param type Mapping name
    * @return true if mapping exists
    */
   private boolean isMappingExist(String index, String type) {
      ClusterState cs = client.admin().cluster().prepareState()
            .setIndices(index).execute().actionGet()
            .getState();
      // Check index metadata existence.
      IndexMetaData imd = cs.getMetaData().index(index);
      if (imd == null){
         return false;
      }
      // Check mapping metadata existence.
      MappingMetaData mdd = imd.mapping(type);
      if (mdd != null){
         return true;
      }
      return false;
   }
   
   private void pushMapping(String index, String type, XContentBuilder xcontent) throws Exception {
      if (logger.isTraceEnabled()){
         logger.trace("pushMapping(" + index + ", " + type + ")");
      }
      
      // If type does not exist, we create it
      boolean mappingExist = isMappingExist(index, type);
      if (!mappingExist) {
         logger.debug("Mapping [" + index + "]/[" + type + "] doesn't exist. Creating it.");

         // Read the mapping json file if exists and use it.
         if (xcontent != null){
            if (logger.isTraceEnabled()){
               logger.trace("Mapping for [" + index + "]/[" + type + "]=" + xcontent.string());
            }
            // Create type and mapping
            PutMappingResponse response = client.admin().indices()
                  .preparePutMapping(index)
                  .setType(type)
                  .setSource(xcontent)
                  .execute().actionGet();       
            if (!response.isAcknowledged()){
               throw new Exception("Could not define mapping for type [" + index + "]/[" + type + "].");
            } else {
               if (logger.isDebugEnabled()){
                  if (mappingExist){
                     logger.debug("Mapping definition for [" + index + "]/[" + type + "] succesfully merged.");
                  } else {
                     logger.debug("Mapping definition for [" + index + "]/[" + type + "] succesfully created.");
                  }
               }
            }
         } else {
            if (logger.isDebugEnabled()){
               logger.debug("No mapping definition for [" + index + "]/[" + type + "]. Ignoring.");
            }
         }
      } else {
         if (logger.isDebugEnabled()){
            logger.debug("Mapping [" + index + "]/[" + type + "] already exists and mergeMapping is not set.");
         }
      }
      if (logger.isTraceEnabled()){
         logger.trace("/pushMapping(" + index + ", " + type + ")");
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
               if (isStarted()){
                  // Scan folder starting from last changes id, then record the new one.
                  Long lastChangesId = getLastChangesIdFromRiver("_lastChangesId");
                  lastChangesId = scan(feedDefinition.getFolder(), lastChangesId);
                  updateRiver("_lastChangesId", lastChangesId);
               } else {
                  logger.info("Google Drive River is disabled for {}", riverName().name());
               }
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

      private boolean isStarted(){
         // Refresh index before querying it.
         client.admin().indices().prepareRefresh("_river").execute().actionGet();
         GetResponse isStartedGetResponse = client.prepareGet("_river", riverName().name(), "_drivestatus").execute().actionGet();
         try{
            if (!isStartedGetResponse.isExists()){
               XContentBuilder xb = jsonBuilder().startObject()
                     .startObject("google-drive")
                        .field("feedname", feedDefinition.getFeedname())
                        .field("status", "STARTED").endObject()
                     .endObject();
               client.prepareIndex("_river", riverName.name(), "_drivestatus").setSource(xb).execute();
               return true;
            } else {
               String status = (String)XContentMapValues.extractValue("google-drive.status", isStartedGetResponse.getSourceAsMap());
               if ("STOPPED".equals(status)){
                  return false;
               }
            }
         } catch (Exception e){
            logger.warn("failed to get status for " + riverName().name() + ", throttling....", e);
         }
         return true;
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

               if (fsState != null){
                  Object lastChanges = fsState.get(lastChangesField);
                  if (lastChanges != null){
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
      
      /** Scan the Google Drive folder for last changes. */
      private Long scan(String folder, Long lastChangesId) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Starting scanning of folder {} since {}", folder, lastChangesId);
         }
         DriveChanges changes = drive.getChanges(lastChangesId);
         
         // Browse change and checks if its indexable before starting.
         for (Change change : changes.getChanges()){
            if (change.getFile() != null && DriveRiverUtil.isIndexable(change.getFile().getTitle(), 
                  feedDefinition.getIncludes(), feedDefinition.getExcludes())){
               if (change.getDeleted()){
                  esDelete(indexName, typeName, change.getFileId());
               } else {
                  indexFile(change.getFile());
               }
            }
         }
         return changes.getLastChangeId();
      }
      
      /** Index a Google Drive file by retrieving its content and building the suitable Json content. */
      private void indexFile(File driveFile){
         if (logger.isDebugEnabled()){
            logger.debug("Trying to index '{}'", driveFile.getTitle());
         }
         
         try{
            byte[] fileContent = drive.getContent(driveFile);
            if (fileContent != null){
               // Parse content using Tika directly.
               String parsedContent = TikaHolder.tika().parseToString(
                     new BytesStreamInput(fileContent, false), new Metadata());
               
               esIndex(indexName, typeName, driveFile.getId(),
                     jsonBuilder()
                        .startObject()
                           .field(DriveRiverUtil.DOC_FIELD_TITLE, driveFile.getTitle())
                           .field(DriveRiverUtil.DOC_FIELD_CREATED_DATE, driveFile.getCreatedDate().getValue())
                           .field(DriveRiverUtil.DOC_FIELD_MODIFIED_DATE, driveFile.getModifiedDate().getValue())
                           .field(DriveRiverUtil.DOC_FIELD_SOURCE_URL, driveFile.getAlternateLink())
                           .startObject("file")
                              .field("_content_type", drive.getMimeType(driveFile))
                              .field("_name", driveFile.getTitle())
                              .field("title", driveFile.getTitle())
                              .field("file", parsedContent)
                           .endObject()
                        .endObject());
               
               if (logger.isDebugEnabled()){
                  logger.debug("Index " + driveFile.getTitle() + " : success");
               }
            } else {
               logger.debug("File content was returned as null");
            }
         } catch (Exception e) {
            logger.warn("Can not index " + driveFile.getTitle() + " : " + e.getMessage());
         }
      }
      
      /** Update river last changes id value.*/
      private void updateRiver(String lastChangesField, Long lastChangesId) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Updating lastChangesField: {}", lastChangesId);
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

      /** Add to bulk an IndexRequest. */
      private void esIndex(String index, String type, String id, XContentBuilder xb) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Indexing in ES " + index + ", " + type + ", " + id);
         }
         if (logger.isTraceEnabled()){
            logger.trace("Json indexed : {}", xb.string());
         }
         bulkProcessor.add(client.prepareIndex(index, type, id).setSource(xb).request());
      }

      /** Add to bulk a DeleteRequest. */
      private void esDelete(String index, String type, String id) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Deleting from ES " + index + ", " + type + ", " + id);
         }
         bulkProcessor.add(client.prepareDelete(index, type, id).request());
      }
   }
}
