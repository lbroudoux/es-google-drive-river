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
package com.github.lbroudoux.elasticsearch.river.drive.rest;

import java.io.IOException;
import java.util.Arrays;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import org.elasticsearch.rest.action.support.RestXContentBuilder;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.DriveScopes;
/**
 * 
 * @author laurent
 */
public class DriveOAuthAction extends BaseRestHandler{

   @Inject
   public DriveOAuthAction(Settings settings, Client client, RestController controller) {
      super(settings, client);

      // Define Drive REST endpoints.
      controller.registerHandler(Method.GET, "/_drive/oauth/{client_id}/{client_secret}", this);
      controller.registerHandler(Method.GET, "/_drive/oauth/{client_id}/{client_secret}/{auth_code}/{auth_code_1}", this);
   }
   
   @Override
   public void handleRequest(RestRequest request, RestChannel channel){
      if (logger.isDebugEnabled()){
         logger.debug("REST DriveOAuthAction called");
      }
      
      String clientId = request.param("client_id");
      String clientSecret = request.param("client_secret");
      String authCode = request.param("auth_code");
      String authCode1 = request.param("auth_code_1");
      
      if (clientId == null || clientSecret == null){
         onFailure(request, channel, new IOException("client_id and client_secret can not be null."));
      }
      
      try{
         XContentBuilder builder = RestXContentBuilder.restContentBuilder(request);
         // We'll use some transport and json factory for sure.
         HttpTransport httpTransport = new NetHttpTransport();
         JsonFactory jsonFactory = new JacksonFactory();
         
         if (authCode == null){
            // It's the first call, we've got to build the authorization url.
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, jsonFactory, clientId, clientSecret, Arrays.asList(DriveScopes.DRIVE_READONLY))
                .setAccessType("offline")
                .setApprovalPrompt("force").build();
            
            builder
               .startObject()
                  .field(new XContentBuilderString("url"), 
                        flow.newAuthorizationUrl().setRedirectUri("urn:ietf:wg:oauth:2.0:oob").build())
               .endObject();
   
         } else {
            // We've got auth code from Google and should request an access token and a refresh token.
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                  httpTransport, jsonFactory, clientId, clientSecret, Arrays.asList(DriveScopes.DRIVE_READONLY))
                  .setAccessType("offline")
                  .setApprovalPrompt("force").build();
            
            // authCode main contain a "/"; recreate it if splitted.
            if (authCode1 != null){
               authCode = authCode + "/" + authCode1;
            }
            
            GoogleTokenResponse response = flow.newTokenRequest(authCode).setRedirectUri("urn:ietf:wg:oauth:2.0:oob").execute();
            
            builder
               .startObject()
                  .field(new XContentBuilderString("accessToken"), response.getAccessToken())
                  .field(new XContentBuilderString("refreshToken"), response.getRefreshToken())
               .endObject();
         }
         channel.sendResponse(new XContentRestResponse(request, RestStatus.OK, builder));
      } catch (IOException ioe){
         onFailure(request, channel, ioe);
      }
   }
   
   /** */
   protected void onFailure(RestRequest request, RestChannel channel, Exception e){
      try{
          channel.sendResponse(new XContentThrowableRestResponse(request, e));
      } catch (IOException ioe){
         logger.error("Sending failure response fails !", e);
      }
      
   }
}