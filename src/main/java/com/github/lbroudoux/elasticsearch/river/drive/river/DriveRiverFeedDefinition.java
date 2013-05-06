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

/**
 * 
 * @author laurent
 */
public class DriveRiverFeedDefinition{
   
   private String feedname;
   private String folder;
   private int updateRate;
   
   private String clientId;
   private String clientSecret;
   private String refreshToken;
   
   
   public DriveRiverFeedDefinition(String feedname, String folder, int updateRate, String clientId, String clientSecret, String refreshToken){
      this.feedname = feedname;
      this.folder = folder;
      this.updateRate = updateRate;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.refreshToken = refreshToken;
   }
   
   public String getFeedname() {
      return feedname;
   }
   public void setFeedname(String feedname) {
      this.feedname = feedname;
   }
   
   public String getFolder() {
      return folder;
   }
   public void setFolder(String folder) {
      this.folder = folder;
   }
   
   public int getUpdateRate() {
      return updateRate;
   }
   public void setUpdateRate(int updateRate) {
      this.updateRate = updateRate;
   }

   public String getClientId() {
      return clientId;
   }
   public void setClientId(String clientId) {
      this.clientId = clientId;
   }

   public String getClientSecret() {
      return clientSecret;
   }
   public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
   }

   public String getRefreshToken() {
      return refreshToken;
   }
   public void setRefreshToken(String refreshToken) {
      this.refreshToken = refreshToken;
   }
}
