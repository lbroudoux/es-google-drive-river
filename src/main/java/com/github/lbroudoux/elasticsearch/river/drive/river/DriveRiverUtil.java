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

import java.util.List;
import java.util.Map;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
/**
 * Utility class for Google Drive indexing management.
 * @author laurent
 */
public class DriveRiverUtil{

   public static final String INDEX_TYPE_DOC = "doc";

   public static final String DOC_FIELD_TITLE = "title";
   public static final String DOC_FIELD_CREATED_DATE = "createdDate";
   public static final String DOC_FIELD_MODIFIED_DATE = "modifiedDate";
   public static final String DOC_FIELD_SOURCE_URL = "source_url";

   /**
    * Build mapping description for Google Drive files.
    * @param type The name of type for Drive files
    * @return A content builder for mapping informations
    * @throws Exception it something goes wrong
    */
   public static XContentBuilder buildDriveFileMapping(String type) throws Exception{
      XContentBuilder xbMapping = jsonBuilder().prettyPrint().startObject()
         .startObject(type).startObject("properties")
         .startObject(DOC_FIELD_TITLE).field("type", "string").field("analyzer","keyword").endObject()
         .startObject(DOC_FIELD_CREATED_DATE).field("type", "date").endObject()
         .startObject(DOC_FIELD_MODIFIED_DATE).field("type", "date").endObject()
         .startObject(DOC_FIELD_SOURCE_URL).field("type", "string").endObject()
         .startObject("file")
            .field("type", "attachment")
               .startObject("fields")
                  .startObject("title").field("store", "yes").endObject()
                  .startObject("file")
                     .field("term_vector", "with_positions_offsets")
                     .field("store", "yes")
                  .endObject()
               .endObject()
         .endObject()
         .endObject().endObject().endObject();
      return xbMapping;
   }
   
   /**
    * Extract array from settings (array or ; delimited String)
    * @param settings Settings
    * @param path Path to settings definition
    * @return Array of settings
    */
   @SuppressWarnings("unchecked")
   public static String[] buildArrayFromSettings(Map<String, Object> settings, String path){
      String[] includes;

      // We manage comma separated format and arrays
      if (XContentMapValues.isArray(XContentMapValues.extractValue(path, settings))) {
         List<String> includesarray = (List<String>) XContentMapValues.extractValue(path, settings);
         int i = 0;
         includes = new String[includesarray.size()];
         for (String include : includesarray) {
            includes[i++] = Strings.trimAllWhitespace(include);
         }
      } else {
         String includedef = (String) XContentMapValues.extractValue(path, settings);
         includes = Strings.commaDelimitedListToStringArray(Strings.trimAllWhitespace(includedef));
      }
      
      String[] uniquelist = Strings.removeDuplicateStrings(includes);
      
      return uniquelist;
   }
   
   /**
    * Tells if a Google Drive file is indexable from its title, based on includes
    * and excludes rules. 
    * @return true if file should be indexed, false otherwise
    */
   public static boolean isIndexable(String fileTitle, List<String> includes, List<String> excludes){
      // If no rules specified, we index everything !
      if ((includes == null && excludes == null) 
            || (includes.isEmpty() && excludes.isEmpty())){
         return true;
      }
      
      // Exclude rules : we know that whatever includes rules are, we should exclude matching files.
      if (excludes != null){
         for (String exclude : excludes){
            String regex = exclude.replace("?", ".?").replace("*", ".*?");
            if (fileTitle.matches(regex)){
               return false;
            }
         }
      }
      
      // Include rules : we should add document if it match include rules.
      if (includes == null || includes.isEmpty()){
         return true;
      }
      if (includes != null){
         for (String include : includes){
            String regex = include.replace("?", ".?").replace("*", ".*?");
            if (fileTitle.matches(regex)){
               return true;
            }
         }
      }
      
      return false;
   }
}
