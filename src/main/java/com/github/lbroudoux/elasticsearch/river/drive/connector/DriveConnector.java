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
import java.util.ArrayList;
import java.util.List;

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
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
/**
 * 
 * @author laurent
 */
public class DriveConnector{

   private static final ESLogger logger = Loggers.getLogger(DriveConnector.class);
   
   private final String clientId;
   private final String clientSecret;
   private final String refreshToken;
   private Drive service; 
   
   public DriveConnector(String clientId, String clientSecret, String refreshToken){
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.refreshToken = refreshToken;
   }
   
   public void connectUserDrive(){
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
   }
   
   /**
    * 
    * @param folder
    * @param lastChangesId
    * @return
    */
   public DriveChanges getChanges(String folder, Long lastChangesId){
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
         request.setStartChangeId(lastChangesId);
      }
      
      Long largestChangesId = null;
      do{
         try{
           ChangeList changes = request.execute();
           if (logger.isDebugEnabled()){
              logger.debug("Found {} items in this changes page", changes.getItems().size());
           }
           result.addAll(changes.getItems());
           request.setPageToken(changes.getNextPageToken());
           largestChangesId = changes.getLargestChangeId();
         } catch (IOException ioe) {
           logger.error("An error occurred while processing changes page: " + ioe);
           request.setPageToken(null);
         }
      } while (request.getPageToken() != null && request.getPageToken().length() > 0);
      
      // Wrap results and latest changes id.
      return new DriveChanges(largestChangesId, result);
   }
   
   /**
    * 
    * @param driveFile
    * @return
    */
   public byte[] getContent(File driveFile){
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
            while (len > 0){
               bos.write(buffer, 0, len); 
               len = is.read(buffer);
            }
            
            // Flush and return result.
            bos.flush();
            return bos.toByteArray();
         } catch (IOException e) {
            e.printStackTrace();
            return null;
         }
         finally {
            if (bos != null){
               try{
                  bos.close();
               } catch (IOException e) {}
            }
            try {
               is.close();
            } catch (IOException e) {}
         }
       } else {
          return null;
       }

   }
}
