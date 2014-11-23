es-google-drive-river
=====================

Google Drive river for Elasticsearch

This river plugin helps to index documents from a Google Drive account.

*WARNING*: For 0.0.1 released version, you need to have the [Attachment Plugin](https://github.com/elasticsearch/elasticsearch-mapper-attachments).

*WARNING*: Starting from 0.0.2, you don't need anymore the [Attachment Plugin](https://github.com/elasticsearch/elasticsearch-mapper-attachments) as we use now directly [Tika](http://tika.apache.org/), see [issue #2](https://github.com/lbroudoux/es-google-drive-river/issues/2).

Versions
--------

<table>
   <thead>
      <tr>
         <td>Google Drive River Plugin</td>
         <td>ElasticSearch</td>
         <td>Attachment Plugin</td>
         <td>Tika</td>
      </tr>
   </thead>
   <tbody>
      <tr>
         <td>master (1.3.1-SNAPSHOT)</td>
         <td>1.3.x</td>
         <td>No more used</td>
         <td>1.4</td>
      </tr>
      <tr>
         <td>1.3.0</td>
         <td>1.3.x</td>
         <td>No more used</td>
         <td>1.4</td>
      </tr>
      <tr>
         <td>1.2.0</td>
         <td>1.2.x</td>
         <td>No more used</td>
         <td>1.4</td>
      </tr>
      <tr>
         <td>0.0.4</td>
         <td>1.0.x and 1.1.x</td>
         <td>No more used</td>
         <td>1.4</td>
      </tr>
      <tr>
         <td>0.0.2</td>
         <td>0.90.0</td>
         <td>No more used</td>
         <td>1.4</td>
      </tr>
      <tr>
         <td>0.0.1</td>
         <td>0.90.0</td>
         <td>1.7.0</td>
         <td></td>
      </tr>
   </tbody>
</table>


Build Status
------------

Travis CI [![Build Status](https://travis-ci.org/lbroudoux/es-google-drive-river.png?branch=master)](https://travis-ci.org/lbroudoux/es-google-drive-river)


Getting Started
===============

Installation
------------

Just install a regular Elasticsearch plugin by typing :

```sh
$ bin/plugin --install com.github.lbroudoux.elasticsearch/google-drive-river/1.3.0
```

This will do the job...

```
-> Installing com.github.lbroudoux.elasticsearch/google-drive-river/1.3.0...
Trying http://download.elasticsearch.org/com.github.lbroudoux.elasticsearch/google-drive-river/google-drive-river-1.3.0.zip...
Trying http://search.maven.org/remotecontent?filepath=com/github/lbroudoux/elasticsearch/google-drive-river/1.3.0/google-drive-river-1.3.0.zip...
Downloading ......DONE
Installed google-drive-river
```


Get Google Drive credentials (clientId, clientSecret and refreshToken)
------------------------------------------

First, you need to login to Google account owning the drive to scan and then create your own application and declare it as using the
Drive API into the [Google APIs Console](https://code.google.com/apis/console/).

In order to do that, you can follow the 1st step of the Drive developers quickstart here : 
(https://developers.google.com/drive/quickstart-java#step_1_enable_the_drive_api).

Once done, you should note your `clientId` and `clientSecret` codes.

You need then to get an Authorization Code from the user owning the Google account (you !) for this new application.

Just open the `_drive` REST Endpoint with your `clientId` and `clientSecret` parameters: http://localhost:9200/_river/oauth/clientId/clientSecret

```sh
$ curl http://localhost:9200/_drive/oauth/{clientId}/{clientSecret}
```
You will get back a URL:

```javascript
{
  "url" : "http://...."
}
```
Open the URL in your browser. You will be asked by Google to allow your application to access your Drive in offline mode. 

Once you get back the success reply from Google, you can get the future river `refreshToken` by pasting gathered Authorization Code and calling

```sh
$ curl http://localhost:9200/_drive/oauth/{clientId}/{clientSecret}/{authorizationCode}
```
You will get back a JSON document like the following :

```javascript
{
  "accessToken" : "yourAccessToken",
  "refreshToken" : "yourRefreshToken"
}
```

`accessToken` is just a one time token ; it is not used later but allow you to ensure access to the target Drive is enable.
Note the given `refreshToken` for later creation of the river into Elasticsearch.


Creating a Google Drive river
------------------------

We create first an index to store our *documents* (optional):

```sh
$ curl -XPUT 'http://localhost:9200/mydocs/' -d '{}'
```

We create the river with the following properties :

* clientId : AAAAAAAAAAAAAAAA
* clientSecret: BBBBBBBBBBBBBBBB
* refreshToken : XXXXXXXXXXXXXXXX
* Google Drive folder to index : `Work` (This is optional. If specified, it should be a root folder)
* Update Rate : every 15 minutes (15 * 60 * 1000 = 900000 ms)
* Get only docs like `*.doc` and `*.pdf`
* Don't index `*.zip` and `*.gz`

```sh
$ curl -XPUT 'http://localhost:9200/_river/mydocs/_meta' -d '{
  "type": "google-drive",
  "google-drive": {
    "clientId": "AAAAAAAAAAAAAAAA",
    "clientSecret": "BBBBBBBBBBBBBBBB",
    "refreshToken": "XXXXXXXXXXXXXXXX",
    "name": "My GDrive feed",
    "folder": "Work",
    "update_rate": 900000,
    "includes": "*.doc,*.pdf",
    "excludes": "*.zip,*.gz"
  }
}'
```
By default, river is using an index that have the same name (`mydocs` in the above example).


Specifying index options
------------------------

Index options can be specified when creating a google-drive-river. The properties are the following :

* Index name : "drivedocs"
* Type of documents : "doc"
* Size of an indexation bulk : 50 (default is 100)

You'll have to use them as follow when creating a river :

```sh
$ curl -XPUT 'http://localhost:9200/_river/mydocs/_meta' -d '{
  "type": "google-drive",
  "google-drive": {
    "clientId": "AAAAAAAAAAAAAAAA",
    "clientSecret": "BBBBBBBBBBBBBBBB",
    "refreshToken": "XXXXXXXXXXXXXXXX",
    "name": "My GDrive feed",
    "folder": "Work",
    "update_rate": 900000,
    "includes": "*.doc,*.pdf",
    "excludes": "*.zip,*.gz"
  },
  "index": {
    "index": "drivedocs",
    "type": "doc",
    "bulk_size": 50
  }
}'
```


Advanced
========

Management actions
------------------

If you need to stop a river, you can call the `_drive` endpoint with your river name followed by the `_stop` command like this :

```sh
GET _drive/mydocs/_stop
```

To restart the river from the previous point, just call the corresponding `_start` endpoint :

```sh
GET _drive/mydocs/_start
```

Autogenerated mapping
---------------------

When the river detect a new type, it creates automatically a mapping for this type.

```javascript
{
  "doc" : {
    "properties" : {
      "title" : {
        "type" : "string",
        "analyzer" : "keyword"
      },
      "createdDate" : {
        "type" : "date",
        "format" : "dateOptionalTime"
      },
      "modifiedDate" : {
        "type" : "date",
        "format" : "dateOptionalTime"
      },
      "file" : {
        "type" : "attachment",
        "fields" : {
          "file" : {
            "type" : "string",
            "store" : "yes",
            "term_vector" : "with_positions_offsets"
          },
          "title" : {
            "type" : "string",
            "store" : "yes"
          }
        }
      }
    }
  }
}
```


License
=======

```
This software is licensed under the Apache 2 license, quoted below.

Copyright 2013 Laurent Broudoux

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
```