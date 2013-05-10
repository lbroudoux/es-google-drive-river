es-google-drive-river
=====================

Google Drive river for Elasticsearch

This river plugin helps to index documents from a Google Drive account.

*WARNING*: You need to have the [Attachment Plugin](https://github.com/elasticsearch/elasticsearch-mapper-attachments).

Versions
--------

<table>
   <thead>
      <tr>
         <td>Google Drive River Plugin</td>
         <td>ElasticSearch</td>
         <td>Attachment Plugin</td>
      </tr>
   </thead>
   <tbody>
      <tr>
         <td>master (0.0.1)</td>
         <td>0.90.0</td>
         <td>1.7.0</td>
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

Get Google Drive credentials (clientId, clientSecret and refreshToken)
------------------------------------------

```sh
$ curl http://localhost:9200/_drive/oauth/clientId/clientSecret
```

```javascript
{
  "url" : "http://...."
}
```
Open the URL in your browser. You will be asked by Google to allow your application to access your Drive in offline mode. 

Once you get back the success reply from Google, you can get the future river refeshToken by pasting gathered authorization code and calling

```sh
$ curl http://localhost:9200/_drive/oauth/clientId/clientSecret/authorizationCode

```javascript
{
  "accessToken" : "yourAccessToken",
  "refreshToken" : "youRefreshToken"
}
```

Creating a Google Drive river
------------------------

We create first an index to store our *documents* (optional):

```sh
$ curl -XPUT 'http://localhost:9200/mydocs/' -d '{}'
```

```sh
$ curl -XPUT 'http://localhost:9200/_river/mydocs/_meta' -d '{
  "type": "google-drive",
  "google-drive": {
    "clientId": "AAAAAAAAAAAAAAAA",
    "clientSecret": "BBBBBBBBBBBBBBBB",
    "refreshToken": "XXXXXXXXXXXXXXXX",
    "secret": "YYYYYYYYYYYYYYYY",
    "name": "My GDrive feed",
    "folder": "Work",
    "update_rate": 900000,
    "includes": "*.doc,*.pdf",
    "excludes": "*.zip,*.gz"
  }
}'
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