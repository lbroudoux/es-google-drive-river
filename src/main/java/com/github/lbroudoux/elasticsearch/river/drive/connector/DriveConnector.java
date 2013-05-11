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
package com.github.lbroudoux.elasticsearch.river.drive.connector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Changes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
/**
 * This is a connector for querying and retrieving files or folders from
 * a Google Drive. Credentials are mandatory for connecting to remote drive.
 * @author laurent
 */
public class DriveConnector{

   private static final ESLogger logger = Loggers.getLogger(DriveConnector.class);
   
   private final String clientId;
   private final String clientSecret;
   private final String refreshToken;
   private String folderName;
   private Drive service;
   private Set<String> subfoldersId;
   
   public DriveConnector(String clientId, String clientSecret, String refreshToken){
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.refreshToken = refreshToken;
   }
   
   /**
    * Actually connect to specified drive, exchanging refresh token for an up-to-date
    * set of credentials. If folder name specified, we also retrieve subfolders to scan. 
    * @param folderName The name of the root folder to scan.
    */
   public void connectUserDrive(String folderName) throws IOException{
      this.folderName = folderName;
      logger.info("Establishing connection to Google Drive");
      // We'll use some transport and json factory for sure.
      HttpTransport httpTransport = new NetHttpTransport();
      JsonFactory jsonFactory = new JacksonFactory();
      
      TokenResponse tokenResponse = null;
      try{
         tokenResponse = new GoogleRefreshTokenRequest(httpTransport, jsonFactory, refreshToken, clientId, clientSecret).execute();
      } catch (IOException ioe){
         logger.error("IOException while refreshing a token request", ioe);
      }
      
      GoogleCredential credential = new GoogleCredential.Builder()
         .setTransport(httpTransport)
         .setJsonFactory(jsonFactory)
         .setClientSecrets(clientId, clientSecret).build()
         .setFromTokenResponse(tokenResponse);
      //credential.setRefreshToken(refreshToken);
      
      service = new Drive.Builder(httpTransport, jsonFactory, credential).build();
      logger.info("Connection established.");
      
      if (folderName != null){
         logger.info("Retrieving scanned subfolders under folder {}, this may take a while...", folderName);
         subfoldersId = getSubfoldersId(folderName);
         logger.info("Subfolders to scan found");
         if (logger.isDebugEnabled()){
            logger.debug("Found {} valid subfolders under folder {}", subfoldersId.size(), folderName);
         }
      }
   }
   
   /**
    * Query Google Drive for getting the last changes since the lastChangesId (may be null
    * if this is the first time).
    * @param lastChangesId The identifier of last changes to start from 
    * @return
    */
   public DriveChanges getChanges(Long lastChangesId){
      if (logger.isDebugEnabled()){
         logger.debug("Getting drive changes since {}", lastChangesId);
      }
      List<Change> result = new ArrayList<Change>();
      Changes.List request = null;
      
      try{
         // Prepare request object for listing changes.
         request = service.changes().list();
      } catch (IOException ioe){
         logger.error("IOException while listing changes on drive service", ioe);
      }
      // Filter last changes if provided.
      if (lastChangesId != null){
         request.setStartChangeId(lastChangesId + 1);
      }
      
      long largestChangesId = -1;
      do{
         try{
           ChangeList changes = request.execute();
           if (logger.isDebugEnabled()){
              logger.debug("Found {} items in this changes page", changes.getItems().size());
              logger.debug("  largest changes id is {}", changes.getLargestChangeId());
           }
           // Filter change based on their parent folder.
           for (Change change : changes.getItems()){
              if (isChangeInValidSubfolder(change)){
                 result.add(change);
              }
           }
           request.setPageToken(changes.getNextPageToken());
           if (changes.getLargestChangeId() > largestChangesId){
              largestChangesId = changes.getLargestChangeId();
           }
         } catch (IOException ioe) {
           logger.error("An error occurred while processing changes page: " + ioe);
           request.setPageToken(null);
         }
      } while (request.getPageToken() != null && request.getPageToken().length() > 0);
      
      // Wrap results and latest changes id.
      return new DriveChanges(largestChangesId, result);
   }
   
   /**
    * Download Google Drive file as byte array.
    * @param driveFile The file to download
    * @return This file bytes or null if something goes wrong.
    */
   public byte[] getContent(File driveFile){
      if (logger.isDebugEnabled()){
         logger.debug("Downloading file content from {}", driveFile.getDownloadUrl());
      }
      // TODO: 'application/vnd.google-apps.document'
      if (driveFile.getDownloadUrl() != null && driveFile.getDownloadUrl().length() > 0){
         InputStream is = null;
         ByteArrayOutputStream bos = null;

         try{
            // Execute GET request on download url and retrieve input and output streams.
            HttpResponse response = service.getRequestFactory()
                  .buildGetRequest(new GenericUrl(driveFile.getDownloadUrl()))
                  .execute();
            is = response.getContent();
            bos = new ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int len = is.read(buffer);
            while (len > 0) {
               bos.write(buffer, 0, len);
               len = is.read(buffer);
            }

            // Flush and return result.
            bos.flush();
            return bos.toByteArray();
         } catch (IOException e) {
            e.printStackTrace();
            return null;
         } finally {
            if (bos != null){
               try{
                  bos.close();
               } catch (IOException e) {
               }
            }
            try{
               is.close();
            } catch (IOException e) {
            }
         }
      } else {
         return null;
      }
   }
   
   /** */
   private boolean isChangeInValidSubfolder(Change change){
      // If no folder specified, change is valid.
      if (folderName == null){
         return true;
      }
      // Else, check if parent of file changed is in valid subfolders.
      if (change.getFile() != null){
         List<ParentReference> references = change.getFile().getParents();
         if (references != null && !references.isEmpty()){
            for (ParentReference reference : references){
               if (subfoldersId.contains(reference.getId())){
                  return true;
               }
            }
         }
      }
      return false;
   }
   
   /** Retrieve all the ids of subfolders under root folder name (recursively). */
   private Set<String> getSubfoldersId(String rootFolderName) throws IOException{
      Set<String> subfoldersId = new TreeSet<String>();
      String rootFolderId = null;
      Files.List request = null;
            
      // 1st step: ensure folder is existing and retrieve its id.
      try{
         request = service.files().list()
               .setMaxResults(2)
               .setQ("title='" + rootFolderName + "' and mimeType='application/vnd.google-apps.folder' and 'root' in parents");
         FileList files = request.execute();
         logger.debug("Found {} files corresponding to searched root folder", files.getItems().size());
         if (files != null && files.getItems().size() != 1){
            throw new FileNotFoundException(rootFolderName + " does not seem to be a valid folder into Google Drive root");
         }
         rootFolderId = files.getItems().get(0).getId();
         logger.debug("Id of searched root folder is {}", rootFolderId);
      } catch (IOException ioe){
         logger.error("IOException while retrieving root folder {} on drive service", rootFolderName, ioe);
         throw ioe;
      }
      
      // 2nd step: retrieve all folders in drive ('cause we cannot get root folder children
      // recursively with a single query) and store them into a map for later filtering.
      Map<String, String> folderIdToParentId = new HashMap<String, String>();
      try{
         request = service.files().list()
               .setMaxResults(Integer.MAX_VALUE)
               .setQ("mimeType='application/vnd.google-apps.folder'");
         FileList files = request.execute();
         for (File folder : files.getItems()){
            List<ParentReference> parents = folder.getParents();
            if (parents != null && !parents.isEmpty()){
               folderIdToParentId.put(folder.getId(), parents.get(0).getId());
            }
         }
      } catch (IOException ioe){
         logger.error("IOException while retrieving all folders on drive service", ioe);
         throw ioe;
      }
      
      // 3rd step: filter folders and store only the ids of children of searched rootFolder.
      for (String folderId : folderIdToParentId.keySet()){
         // If the root folder, just add it.
         if (folderId.equals(rootFolderId)){
            subfoldersId.add(folderId);
         } else {
            // Else, check the parents. 
            List<String> parents = collectParents(folderId, folderIdToParentId);
            logger.debug("Parents of {} are {}", folderId, parents);
            // Last parent if the root of the drive, so searched root folder is the one before.
            if (parents.size() > 1 && parents.get(parents.size() - 2).equals(rootFolderId)){
               // Found a valid path to root folder, add folder and its parents but remove root before.
               subfoldersId.add(folderId);
               parents.remove(parents.size() - 1);
               subfoldersId.addAll(parents);
            }
         }
      }
      if (logger.isDebugEnabled()){
         logger.debug("Subfolders Id to scan are {}", subfoldersId);
      }
      return subfoldersId;
   }
   
   /** Get the list of parents Id in ascending order. */
   private List<String> collectParents(String folderId, Map<String, String> folderIdToParentId){
      String parentId = folderIdToParentId.get(folderId);
      if (logger.isTraceEnabled()){
         logger.trace("Direct parent of {} is {}", folderId, parentId);
      }
      List<String> ancestors = new ArrayList<String>();
      ancestors.add(parentId);
      
      if (folderIdToParentId.containsKey(parentId)){
         ancestors.addAll(collectParents(parentId, folderIdToParentId));
         return ancestors;
      }
      return ancestors;
   }
}
