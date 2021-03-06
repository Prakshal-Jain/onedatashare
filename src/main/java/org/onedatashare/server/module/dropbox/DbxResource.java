/**
 ##**************************************************************
 ##
 ## Copyright (C) 2018-2020, OneDataShare Team, 
 ## Department of Computer Science and Engineering,
 ## University at Buffalo, Buffalo, NY, 14260.
 ## 
 ## Licensed under the Apache License, Version 2.0 (the "License"); you
 ## may not use this file except in compliance with the License.  You may
 ## obtain a copy of the License at
 ## 
 ##    http://www.apache.org/licenses/LICENSE-2.0
 ## 
 ## Unless required by applicable law or agreed to in writing, software
 ## distributed under the License is distributed on an "AS IS" BASIS,
 ## WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ## See the License for the specific language governing permissions and
 ## limitations under the License.
 ##
 ##**************************************************************
 */


package org.onedatashare.server.module.dropbox;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.files.*;
import org.onedatashare.server.model.core.*;
import org.onedatashare.server.model.error.NotFoundException;
import org.onedatashare.server.service.ODSLoggerService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Resource class that provides services specific to Dropbox endpoint.
 */
public class DbxResource extends Resource<DbxSession, DbxResource> {

  DbxResource(DbxSession session, String path) {
    super(session, path);
  }

  @Override
  public Mono<DbxResource> select(String name) {
    return getSession().select(name);
  }

  public Flux<String> list() {
    return initialize().flux().flatMap(resource -> {
      ListFolderResult listing = null;
      try {
        listing = getSession().getClient().files().listFolder(getPath());
      } catch (DbxException e) {
        e.printStackTrace();
      }
      return Flux.fromIterable(listing.getEntries()).map(Metadata::getName);
    });
  }

  public Mono<DbxResource> mkdir() {
    return initialize().doOnSuccess(resource -> {
      try {
        resource.getSession().getClient().files().createFolderV2(getPath());
      } catch (DbxException e) {
        e.printStackTrace();
      }
    });
  }

  public Mono<DbxResource> delete() {
    return initialize().map(resource -> {
      try {
        resource.getSession().getClient().files().deleteV2(getPath());
      } catch (DbxException e) {
        e.printStackTrace();
      }
      return resource;
    });
  }

  public Mono<Stat> stat() {
    return initialize().map(DbxResource::onStat);
  }

  public Stat onStat() {
    Stat stat = new Stat();
    ListFolderResult data = null;
    Metadata mData = null;
    try {
      if (getPath().equals("/")) {
        data = getSession().getClient().files().listFolder("");
      } else {
        try {
          data = getSession().getClient().files().listFolder(getPath());
        } catch (ListFolderErrorException e) {
          mData = getSession().getClient().files().getMetadata(getPath());
        }
      }
      if (data == null && mData == null)
        throw new NotFoundException();
      if (data == null) {
        stat = mDataToStat(mData);
      } else {
        if (!data.getEntries().isEmpty()) {
          stat = mDataToStat(data.getEntries().iterator().next());
        }
        stat.setDir(true);
        stat.setFile(false);
      }

      stat.setName(getPath());

      if (stat.isDir()) {
        ListFolderResult lfr = null;
        if (stat.getName().equals("/")) {
          lfr = getSession().getClient().files().listFolder("");
        } else {
          // If the metadata is a directory
          if (getSession().getClient().files().getMetadata(getPath()) instanceof FolderMetadata) {
            // list the directory files
            lfr = getSession().getClient().files().listFolder(getPath());
          }
          // If the metadata is a file
          else if (getSession().getClient().files().getMetadata(getPath()) instanceof FileMetadata) {
            // Return the metadata as a stat object
            stat = mDataToStat(getSession().getClient().files().getMetadata(getPath()));
          }
        }
        List<Stat> sub = new LinkedList<>();
        for (Metadata child : lfr.getEntries())
          sub.add(mDataToStat(child));
        stat.setFiles(sub);
      }
    } catch (DbxException e) {
      e.printStackTrace();
    }
    return stat;
  }

  @Override
  public Mono<Stat> getTransferStat(){
    return initialize()
            .map(DbxResource::onStat)
            .map(s ->{
              List<Stat> sub = new LinkedList<>();
              long directorySize = 0L;
              try{
                if(s.isDir())
                  directorySize = buildDirectoryTree(sub, getSession().getClient().files().listFolder(getPath()), "/");
                else{
                  setFileResource(true);
                  sub.add(s);
                  directorySize = s.getSize();
                }
              }
              catch (DbxException e) {
                e.printStackTrace();
              }
              s.setFilesList(sub);
              s.setSize(directorySize);
              return s;
            });
  }

  public Long buildDirectoryTree(List<Stat> sub, ListFolderResult lfr, String relativeDirName) throws DbxException{
    long directorySize = 0L;
    for(Metadata childNode : lfr.getEntries()){
      if(childNode instanceof FileMetadata){
        Stat fileStat = mDataToStat(childNode);
        fileStat.setName(relativeDirName + fileStat.getName());
        directorySize += fileStat.getSize();
        sub.add(fileStat);
      }
      else if(childNode instanceof FolderMetadata){
        directorySize += buildDirectoryTree(sub, getSession().getClient().files().listFolder(((FolderMetadata) childNode).getId()),
                                            relativeDirName + childNode.getName()+"/");
      }
    }
    return directorySize;
  }

  private Stat mDataToStat(Metadata data) {
    Stat stat = new Stat(data.getName());
    if (data instanceof FileMetadata) {
      FileMetadata file = (FileMetadata) data;
      stat.setFile(true);
      stat.setSize(file.getSize());
      stat.setTime(file.getClientModified().getTime() / 1000);
    }
    if (data instanceof FolderMetadata) {
      stat.setDir(true);
    }
    return stat;
  }

  public DbxTap tap() {
    DbxTap dbxTap = new DbxTap();
    return dbxTap;
  }

  public DbxDrain sink() {
    return new DbxDrain().start();
  }

  public DbxDrain sink(Stat stat){
    return new DbxDrain().start(getPath() + stat.getName());
  }

  public class DbxTap implements Tap {
    DownloadBuilder downloadBuilder;
    long size;

    @Override
    public Flux<Slice> tap(Stat stat, long sliceSize) {
      String downloadPath = "";
      if(!isFileResource())
        downloadPath += getPath();
      downloadBuilder = getSession().getClient().files().downloadBuilder(downloadPath +stat.getName());
      size = stat.getSize();
      return tap(sliceSize);
    }

    public Flux<Slice> tap(long sliceSize) {

      return Flux.generate(
              () -> 0L,
              (state, sink) -> {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                if (state + sliceSize < size) {
                  try {
                    downloadBuilder.range(state, sliceSize).start().download(outputStream);
                  } catch (DbxException | IOException e) {
                    e.printStackTrace();
                  }
                  sink.next(new Slice(outputStream.toByteArray()));
                  try {
                    outputStream.close();
                  } catch (IOException e) {
                    e.printStackTrace();
                  }
                } else {
                  try {
                    downloadBuilder.range(state, size - state).start().download(outputStream);
                  } catch (DbxException | IOException e) {
                    e.printStackTrace();
                  }
                  sink.next(new Slice(outputStream.toByteArray()));
                  sink.complete();
                }
                return state + sliceSize;
              });
    }
  }

  class DbxDrain implements Drain {
    String drainPath = getPath();
    long uploaded = 0L;
    InputStream in = new ByteArrayInputStream(new byte[]{});
    String sessionId;
    UploadSessionCursor cursor;

    public DbxDrain start(String drainPath){
      this.drainPath = drainPath;
      return start();
    }

    public DbxDrain start() {
      try {
        sessionId = getSession().getClient().files().uploadSessionStart()
                .uploadAndFinish(in, 0L)
                .getSessionId();
        cursor = new UploadSessionCursor(sessionId, uploaded);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return this;
    }

    public void drain(Slice slice) {
      InputStream sliceInputStream = new ByteArrayInputStream(slice.asBytes());
      try {
        getSession().getClient().files().uploadSessionAppendV2(cursor)
                .uploadAndFinish(sliceInputStream, slice.length());
      } catch (DbxException | IOException e) {
        e.printStackTrace();
      }
      uploaded += slice.length();
      cursor = new UploadSessionCursor(sessionId, uploaded);
    }

    public void finish() {
      CommitInfo commitInfo = CommitInfo.newBuilder(drainPath)
              .withMode(WriteMode.ADD)
              .withClientModified(new Date())
              .build();
      try {
        getSession().getClient().files().uploadSessionFinish(cursor, commitInfo)
                    .uploadAndFinish(in, 0L);
      } catch (DbxException | IOException e) {
        e.printStackTrace();
      }
    }
  }

  public Mono<String> generateDownloadLink(){
    String downloadLink="";
    try {
//      downloadLink = session.client.sharing().createSharedLinkWithSettings(path).getUrl();    // throws an exception if a shared link already exists
      downloadLink = getSession().getClient().files().getTemporaryLink(getPath()).getLink();    //temporary link valid for 4 hours

    }
    catch(DbxException dbxe){
      ODSLoggerService.logError("Error encountered while generating shared link for " + getPath(), dbxe);
    }
    return Mono.just(downloadLink);
  }
}
