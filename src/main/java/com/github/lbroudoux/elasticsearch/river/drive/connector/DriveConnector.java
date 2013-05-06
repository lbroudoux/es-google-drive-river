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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Changes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
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
   
   public void getChanges(String folder, Long lastChangesId){
      if (logger.isDebugEnabled()){
         logger.debug("Getting drive changes since {}", lastChangesId);
      }
      List<Change> result = new ArrayList<Change>();
      Changes.List request = null;
      
      try{
         request = service.changes().list();
      } catch (IOException ioe){
         logger.error("IOException while listing changes on drive service", ioe);
      }
      
      if (lastChangesId != null){
         request.setStartChangeId(lastChangesId);
      }
      do{
         try{
           ChangeList changes = request.execute();
           if (logger.isDebugEnabled()){
              logger.debug("Found {} items in this changes page", changes.getItems().size());
           }
           result.addAll(changes.getItems());
           request.setPageToken(changes.getNextPageToken());
         } catch (IOException ioe) {
           logger.error("An error occurred: " + ioe);
           request.setPageToken(null);
         }
      } while (request.getPageToken() != null && request.getPageToken().length() > 0);
   }
   
   private byte[] getContent(){
      return null;
   }
}
