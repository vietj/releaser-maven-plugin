package com.julienviet.releaser;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;

import java.util.*;

public class Proxy extends AbstractVerticle {

  public interface Listener {

    Listener DEFAULT = new Listener() {};

    default void onStagingCreate(String profileId) {}
    default void onStagingSucceded(String profileId, String repoId) {}
    default void onStagingFailed(String profileId, Throwable cause) {}
    default void onResourceCreate(String uri) {}
    default void onResourceSucceeded(String uri) {}
    default void onResourceFailed(String uri, Throwable cause) {}
  }

  private Listener listener;
  private String stagingHost;
  private int stagingPort;
  private boolean stagingSsl;
  private boolean stagingPipelining;
  private boolean stagingKeepAlive;
  private int stagingPipeliningLimit;
  private int stagingMaxPoolSize;
  private String stagingProfileId;
  private String stagingUsername;
  private String stagingPassword;
  private int port;
  private String repositoryId;

  private HttpServer server;
  private HttpClient client;
  private Staging staging;

  public Proxy(ProxyOptions options) {
    this(options, Listener.DEFAULT);
  }

  public Proxy(ProxyOptions options, Listener listener) {
    this.stagingHost = options.getStagingHost();
    this.stagingPort = options.getStagingPort();
    this.stagingSsl = options.isStagingSsl();
    this.stagingPipelining = options.isStagingPipelining();
    this.stagingKeepAlive = options.isStagingKeepAlive();
    this.stagingPipeliningLimit = options.getStagingPipeliningLimit();
    this.stagingMaxPoolSize = options.getStagingMaxPoolSize();
    this.stagingProfileId = options.getStagingProfileId();
    this.stagingUsername = options.getStagingUsername();
    this.stagingPassword = options.getStagingPassword();
    this.port = options.getPort();
    this.repositoryId = options.getRepositoryId();
    this.listener = listener;
  }

  @Override
  public void start(Promise<Void> startPromise) {
    HttpClientOptions clientOptions = new HttpClientOptions();
    clientOptions.setDefaultPort(stagingPort);
    clientOptions.setDefaultHost(stagingHost);
    clientOptions.setSsl(stagingSsl);
    clientOptions.setPipelining(stagingPipelining);
    clientOptions.setKeepAlive(stagingKeepAlive);
    clientOptions.setPipeliningLimit(stagingPipeliningLimit);
    clientOptions.setTrustAll(true);
    PoolOptions poolOptions = new PoolOptions();
    poolOptions.setHttp1MaxSize(1);
    client = vertx.createHttpClient(clientOptions, poolOptions);
    createStagingRepo()
      .compose(result -> {
        staging = result;
        server = vertx.createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true))
          .requestHandler(staging::handleRequest);
        return server.listen(port);
      })
      .<Void>mapEmpty()
      .onComplete(startPromise);
  }

  private RequestOptions createBaseRequest(HttpMethod method, String uri) {
    RequestOptions request = new RequestOptions();
    request.setMethod(method);
    request.setURI(uri);
    request.putHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((stagingUsername + ":" + stagingPassword).getBytes()));
    request.putHeader("Cache-control", "no-cache");
    request.putHeader("Cache-store", "no-store");
    request.putHeader("Pragma", "no-cache");
    request.putHeader("Expires", "0");
    request.putHeader("User-Agent", "Apache-Maven/3.5.0 (Java 1.8.0_112; Mac OS X 10.13)");
    return request;
  }

  private String invalidResponse(HttpMethod method, String requestUri, int status, Buffer body) {
    StringBuilder msg = new StringBuilder("InvalidResponse[" + method + ", " + requestUri + ", " + status);
    if (body != null && body.length() > 0) {
      msg.append(", ");
      msg.append(body);
    }
    msg.append("]");
    return msg.toString();
  }

  private Future<Staging> createStagingRepo() {
    String requestUri = "/service/local/staging/profiles/" + stagingProfileId + "/start";
    listener.onStagingCreate(stagingProfileId);
    RequestOptions post = createBaseRequest(HttpMethod.POST, requestUri);
    post.putHeader("Content-Type", "application/xml");
    return client.request(post)
      .compose(request -> request
        .send(Buffer.buffer("<promoteRequest>\n" +
          "  <data>\n" +
          "    <description>test description</description>\n" +
          "  </data>\n" +
          "</promoteRequest>\n"))
        .expecting(HttpResponseExpectation.SC_CREATED)
        .compose(HttpClientResponse::body)
        .map(body -> {
          String content = body.toString();
          int from = content.indexOf("<stagedRepositoryId>");
          int to = content.indexOf("</stagedRepositoryId>");
          if (from != -1 && to != -1) {
            String repoId = content.substring(from + "<stagedRepositoryId>".length(), to);
            return new Staging(repoId);
          } else {
            throw new VertxException(invalidResponse(HttpMethod.POST, requestUri, 201, body));
          }
        }));
  }

  private class Staging {

    private final String id;
    private final Map<String, Resource> map = new HashMap<>();

    Staging(String id) {
      this.id = id;
    }

    private class Resource {

      private final String uri;
      private Buffer content;
      private boolean stale;
      private Future<?> upload;

      private Resource(String uri) {
        this.uri = uri;
      }

      private void check() {
        if (upload == null) {
          stale = false;
          upload = upload();
          upload.onComplete(ar -> {
            if (ar.failed()) {
              stale = true;
            }
            check();
          });
        } else if (upload.isComplete()) {
          if (stale) {
            upload = null;
            check();
          }
        }
      }

      private Future<?> upload() {
        Buffer requestBody = content;
        String requestUri = "/service/local/staging/deployByRepositoryId/" + id + uri;
        listener.onResourceCreate(requestUri);
        RequestOptions put = createBaseRequest(HttpMethod.PUT, requestUri);
        return client.request(put)
          .compose(request -> request
            .send(requestBody)
            .expecting(HttpResponseExpectation.SC_CREATED)
            .andThen(ar -> {
              if (ar.succeeded()) {
                listener.onResourceSucceeded(requestUri);
              } else {
                String failure = invalidResponse(HttpMethod.PUT, requestUri, 201, requestBody);
                listener.onResourceFailed(requestUri, new VertxException(failure));
              }
            }));
      }
    }

    private void handleRequest(HttpServerRequest req) {
      HttpMethod method = req.method();
      String path = req.path();
      if (method == HttpMethod.OPTIONS) {
        req.response().putHeader("Allow", "OPTIONS, GET, PUT").end();
      } else if (method == HttpMethod.PUT) {
        req.bodyHandler(body -> {
          Resource res = map.computeIfAbsent(path, Resource::new);
          res.content = body;
          res.stale = true;
          req.response().setStatusCode(201).end();
          res.check();
        });
      } else if (method == HttpMethod.GET) {
        Resource resource = map.get(path);
        if (resource == null) {
          req.response().setStatusCode(404).end();
        } else {
          req.response().end(resource.content);
        }
      }
    }
  }
}
