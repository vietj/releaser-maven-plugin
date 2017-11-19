package com.julienviet.releaser;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.impl.NoStackTraceThrowable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Proxy extends AbstractVerticle {

  public interface Listener {

    Listener DEFAULT = new Listener() {};

    default void onStagingCreate(String profileId) {}
    default void onStagingSucceded(String profileId, String repoId) {}
    default void onSuccessFailed(String profileId, Throwable cause) {}
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

  private CompletableFuture<Staging> staging;

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
  public void start(Future<Void> startFuture) throws Exception {
    HttpClientOptions options = new HttpClientOptions();
    options.setDefaultPort(stagingPort);
    options.setDefaultHost(stagingHost);
    options.setSsl(stagingSsl);
    options.setPipelining(stagingPipelining);
    options.setKeepAlive(stagingKeepAlive);
    options.setPipeliningLimit(stagingPipeliningLimit);
    options.setMaxPoolSize(stagingMaxPoolSize);
    options.setTrustAll(true);
    client = vertx.createHttpClient(options);
    server = vertx.createHttpServer()
        .requestHandler(this::handle)
        .listen(port, ar -> startFuture.handle(ar.mapEmpty()));
  }

  private void handle(HttpServerRequest req) {
    req.pause();
    if (staging == null) {
      staging = new CompletableFuture<>();
      if (repositoryId != null) {
        staging.complete(new Staging(repositoryId));
      } else {
        createStagingRepo(staging);
      }
    }
    staging.whenComplete((staging, err) -> {
      if (err == null) {
        staging.handleRequest(req);
      } else {
        req.resume();
        req.response().setStatusCode(500).end();
      }
    });
  }

  private HttpClientRequest createBaseRequest(HttpMethod method, String uri) {
    HttpClientRequest request = client.request(method, uri);
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

  private void createStagingRepo(CompletableFuture<Staging> resultHandler) {
    Future<String> fut = Future.future();
    fut.setHandler(ar -> {
      if (ar.succeeded()) {
        listener.onStagingSucceded(stagingProfileId, ar.result());
        resultHandler.complete(new Staging(ar.result()));
      } else {
        listener.onSuccessFailed(stagingProfileId, ar.cause());
        resultHandler.completeExceptionally(fut.cause());
      }
    });
    String requestUri = "/service/local/staging/profiles/" + stagingProfileId + "/start";
    listener.onStagingCreate(stagingProfileId);
    HttpClientRequest post = createBaseRequest(HttpMethod.POST, requestUri);
    post.putHeader("Content-Type", "application/xml");
    post.exceptionHandler(fut::tryFail);
    post.handler(resp -> {
      resp.bodyHandler(body -> {
        if (resp.statusCode() == 201) {
          String content = body.toString();
          int from = content.indexOf("<stagedRepositoryId>");
          int to = content.indexOf("</stagedRepositoryId>");
          if (from != -1 && to != -1) {
            String repoId = content.substring(from + "<stagedRepositoryId>".length(), to);
            fut.tryComplete(repoId);
            return;
          }
        }
        fut.tryFail(invalidResponse(HttpMethod.POST, requestUri, resp.statusCode(), body));
      });
    });
    post.end(Buffer.buffer("<promoteRequest>\n" +
        "  <data>\n" +
        "    <description>test description</description>\n" +
        "  </data>\n" +
        "</promoteRequest>\n"));

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
      private Future<Void> upload;

      private Resource(String uri) {
        this.uri = uri;
      }

      private void check() {
        if (upload == null) {
          stale = false;
          upload = Future.future();
          upload.setHandler(ar -> {
            if (ar.failed()) {
              stale = true;
            }
            check();
          });
          upload(upload);
        } else if (upload.isComplete()) {
          if (stale) {
            upload = null;
            check();
          }
        }
      }

      private void upload(Future<Void> result) {
        Buffer requestBody = content;
        String requestUri = "/service/local/staging/deployByRepositoryId/" + id + uri;
        listener.onResourceCreate(requestUri);
        Future<Void> fut = Future.future();
        fut.setHandler(ar -> {
          if (ar.succeeded()) {
            result.tryComplete();
          } else {
            result.tryFail("Failed to upload " + requestUri + ": " + ar.cause().getMessage());
          }
        });
        HttpClientRequest put = createBaseRequest(HttpMethod.PUT, requestUri);
        put.handler(resp -> {
          resp.bodyHandler(e -> {
            if (resp.statusCode() == 201) {
              listener.onResourceSucceeded(requestUri);
              fut.tryComplete();
            } else {
              String failure = invalidResponse(HttpMethod.PUT, requestUri, resp.statusCode(), requestBody);
              listener.onResourceFailed(requestUri, new NoStackTraceThrowable(failure));
              fut.tryFail(failure);
            }
          });
        });
        put.exceptionHandler(fut::tryFail);
        put.end(requestBody);
      }
    }

    private void handleRequest(HttpServerRequest req) {
      req.resume();
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
