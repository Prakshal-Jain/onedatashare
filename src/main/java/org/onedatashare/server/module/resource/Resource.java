package org.onedatashare.server.module.resource;

import lombok.NoArgsConstructor;
import org.onedatashare.server.model.credential.EndpointCredential;
import org.onedatashare.server.model.request.TransferJobRequest;
import reactor.core.publisher.Mono;

import java.io.UnsupportedEncodingException;
import java.util.List;

import static org.onedatashare.server.model.request.TransferJobRequest.Source;

@NoArgsConstructor
public abstract class Resource {
    protected EndpointCredential credential;

    public Resource(EndpointCredential credential){
        this.credential = credential;
    }

    public abstract Mono<List<TransferJobRequest.EntityInfo>> listAllRecursively(Source source);

    public String pathFromUri(String uri) throws UnsupportedEncodingException {
        String path = "";
        path = java.net.URLDecoder.decode(path, "UTF-8");
        return path;
    }
}
