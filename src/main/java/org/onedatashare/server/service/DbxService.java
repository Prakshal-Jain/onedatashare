package org.onedatashare.server.service;

import org.onedatashare.server.model.core.*;
import org.onedatashare.server.model.credential.OAuthCredential;
import org.onedatashare.server.model.error.AuthenticationRequired;
import org.onedatashare.server.model.error.NotFoundException;
import org.onedatashare.server.model.useraction.UserActionResource;
import org.onedatashare.server.model.useraction.UserAction;
import org.onedatashare.server.module.dropbox.DbxResource;
import org.onedatashare.server.module.dropbox.DbxSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Service
public class DbxService implements ResourceService{

    @Autowired
    private UserService userService;

    @Autowired
    private JobService jobService;

    public Mono<DbxResource> getDbxResourceWithUserActionUri(String cookie, UserAction userAction) {
        if(userAction.getCredential().isTokenSaved()) {
            return userService.getLoggedInUser(cookie)
                    .handle((usr, sink) -> {
                        if(userAction.getCredential() == null || userAction.getCredential().getUuid() == null) {
                            sink.error(new AuthenticationRequired("oauth"));
                        }
                        Map credMap = usr.getCredentials();
                        Credential credential = (Credential) credMap.get(UUID.fromString(userAction.getCredential().getUuid()));
                        if(credential == null){
                            sink.error(new NotFoundException("Credentials for the given UUID not found"));
                        }
                        sink.next(credential);
                    })
                    .map(credential -> new DbxSession(URI.create(userAction.getUri()), (Credential) credential))
                    .flatMap(DbxSession::initialize)
                    .flatMap(dbxSession -> dbxSession.select(pathFromDbxUri(userAction.getUri())));
        }
        else{
            return Mono.just(new OAuthCredential(userAction.getCredential().getToken()))
                    .map(oAuthCred -> new DbxSession(URI.create(userAction.getUri()), oAuthCred))
                    .flatMap(DbxSession::initialize)
                    .flatMap(dbxSession -> {
                        String path = pathFromDbxUri(userAction.getUri());
                        return dbxSession.select(path);
                    });
        }
    }

    public Mono<DbxResource> getDbxResourceWithJobSourceOrDestination(String cookie, UserActionResource userActionResource) {
        final String path = pathFromDbxUri(userActionResource.getUri());
        return userService.getLoggedInUser(cookie)
                .map(User::getCredentials)
                .map(uuidCredentialMap ->
                        uuidCredentialMap.get(UUID.fromString(userActionResource.getCredential().getUuid())))
                .map(credential -> new DbxSession(URI.create(userActionResource.getUri()), credential))
                .flatMap(DbxSession::initialize)
                .flatMap(dbxSession -> dbxSession.select(path));
    }

    public String pathFromDbxUri(String uri) {
        String path = "";
        if(uri.contains(ODSConstants.DROPBOX_URI_SCHEME)){
            path = uri.substring(ODSConstants.DROPBOX_URI_SCHEME.length() - 1);
        }
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return path;
    }

    public Mono<Stat> list(String cookie, UserAction userAction) {
        return getDbxResourceWithUserActionUri(cookie, userAction).flatMap(DbxResource::stat);
    }

    public Mono<Boolean> mkdir(String cookie, UserAction userAction) {
        return getDbxResourceWithUserActionUri(cookie, userAction)
                .flatMap(DbxResource::mkdir)
                .map(r -> true);
    }

    public Mono<Boolean> delete(String cookie, UserAction userAction) {
        return getDbxResourceWithUserActionUri(cookie, userAction)
                .flatMap(DbxResource::delete)
                .map(val -> true);
    }

    public Mono<Job> submit(String cookie, UserAction userAction) {
        return userService.getLoggedInUser(cookie)
                .map(user -> {
                    Job job = new Job(userAction.getSrc(), userAction.getDest());
                    job.setStatus(JobStatus.scheduled);
                    job = user.saveJob(job);
                    userService.saveUser(user).subscribe();
                    return job;
                })
                .flatMap(jobService::saveJob)
                .doOnSuccess(job -> processTransferFromJob(job, cookie))
                .subscribeOn(Schedulers.elastic());
    }

    @Override
    public Mono<String> download(String cookie, UserAction userAction) {
        return getDbxResourceWithUserActionUri(cookie,userAction)
                .flatMap(DbxResource::generateDownloadLink).subscribeOn(Schedulers.elastic());
    }

    public void processTransferFromJob(Job job, String cookie) {
        Transfer<DbxResource, DbxResource> transfer = new Transfer<>();
        getDbxResourceWithJobSourceOrDestination(cookie, job.getSrc())
                .map(transfer::setSource)
                .flatMap(t -> getDbxResourceWithJobSourceOrDestination(cookie, job.getDest()))
                .map(transfer::setDestination)
                .flux()
                .flatMap(transfer1 -> transfer1.start(1L << 20))
                .doOnSubscribe(s -> job.setStatus(JobStatus.transferring))
                .doFinally(s -> {
                    job.setStatus(JobStatus.complete);
                    jobService.saveJob(job).subscribe();
                })
                .map(job::updateJobWithTransferInfo)
                .flatMap(jobService::saveJob)
                .subscribe();
    }

    public Mono<String> getDownloadURL(String cookie, UserAction userAction){
        return getDbxResourceWithUserActionUri(cookie,userAction)
                .flatMap(DbxResource::generateDownloadLink).subscribeOn(Schedulers.elastic());
    }

}
