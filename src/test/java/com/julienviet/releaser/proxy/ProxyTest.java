package com.julienviet.releaser.proxy;

import com.julienviet.releaser.Proxy;
import com.julienviet.releaser.ProxyOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(VertxUnitRunner.class)
public class ProxyTest {

  static class Resource {
    final List<Buffer> versions = Collections.synchronizedList(new ArrayList<Buffer>());
  }

  Vertx vertx;
  HttpClient client;
  long repoSeq = 1000;
  Map<String, Repo> repoMap = new ConcurrentHashMap<>();
  Pattern profileURLMatcher = Pattern.compile("/service/local/staging/profiles/([^/]+)/start");
  Pattern resourceURLMatcher = Pattern.compile("/service/local/staging/deployByRepositoryId/([^/]+)/(.*)");

  Proxy.Listener proxyListener = Proxy.Listener.DEFAULT;
  Repo.Listener repoListener = Repo.Listener.DEFAULT;

  static class Repo extends ConcurrentHashMap<String, Resource> {

    final ProxyTest proxyTest;
    final String profileId;
    final String id;

    public Repo(ProxyTest proxyTest, String profileId, String id) {
      this.proxyTest = proxyTest;
      this.profileId = profileId;
      this.id = id;
    }

    boolean handlePut(String uri, Buffer content) {
      if (proxyTest.repoListener.handlePut(uri, content)) {
        computeIfAbsent(uri, p -> new Resource()).versions.add(content);
        return true;
      } else {
        return false;
      }
    }

    interface Listener {
      Listener DEFAULT = new Listener() {};
      default boolean handlePut(String uri, Buffer content) {
        return true;
      }
    }
  }

  private void waitUntil(BooleanSupplier condition, Handler<AsyncResult<Void>> completionHandler) {
    waitUntil(0, condition, completionHandler);
  }
  private void waitUntil(int times, BooleanSupplier condition, Handler<AsyncResult<Void>> completionHandler) {
    if (condition.getAsBoolean()) {
      completionHandler.handle(Future.succeededFuture());
    } else {
      vertx.setTimer(10, id -> {
        if (times < 10000) {
          waitUntil(times + 1, condition, completionHandler);
        } else {
          completionHandler.handle(Future.failedFuture(new TimeoutException()));
        }
      });
    }
  }

  @Before
  public void before(TestContext ctx) {
    vertx = Vertx.vertx();
    HttpServer stagingServer = vertx.createHttpServer()
        .requestHandler(req -> {
          req.bodyHandler(body -> {
            HttpMethod method = req.method();
            String path = req.path();
            if (method == HttpMethod.PUT) {
              Matcher matcher = resourceURLMatcher.matcher(path);
              if (matcher.matches()) {
                String repoId = matcher.group(1);
                Repo repo = repoMap.get(repoId);
                if (repo != null) {
                  String uri = matcher.group(2);
                  if (repo.handlePut(uri, body)) {
                    req.response().setStatusCode(201).end();
                  } else {
                    req.response().setStatusCode(500).end();
                  }
                  return;
                }
              }
            } else if (method == HttpMethod.POST) {
              Matcher matcher = profileURLMatcher.matcher(path);
              if (matcher.matches()) {
                String profile = matcher.group(1);
                String repoId = "test-" + repoSeq++;
                Repo value = new Repo(ProxyTest.this, profile, repoId);
                if (repoMap.putIfAbsent(repoId, value) == null) {
                  req.response().setStatusCode(201).end(
                      "<promoteResponse>" +
                          "<data>" +
                          "<stagedRepositoryId>" + repoId + "</stagedRepositoryId>" +
                          "</data>" +
                          "</promoteResponse>");
                  return;
                }
              }
            }
            req.response().setStatusCode(500).end();
          });
        }).listen(8081, ctx.asyncAssertSuccess());
    vertx.deployVerticle(new Proxy(new ProxyOptions()
            .setStagingProfileId("my_profile")
            .setStagingHost("localhost")
            .setStagingPort(8081)
            .setStagingSsl(false)
            .setStagingKeepAlive(true)
            .setStagingPipelining(true)
            .setStagingMaxPoolSize(1)
            .setPort(8080), new Proxy.Listener() {
          public void onStagingCreate(String profileId) {  proxyListener.onStagingCreate(profileId); }
          public void onStagingSucceded(String profileId, String repoId) { proxyListener.onStagingSucceded(profileId, repoId); }
          public void onStagingFailed(String profileId, Throwable cause) { proxyListener.onStagingFailed(profileId, cause); }
          public void onResourceCreate(String uri) { proxyListener.onResourceCreate(uri); }
          public void onResourceSucceeded(String uri) { proxyListener.onResourceSucceeded(uri); }
          public void onResourceFailed(String uri, Throwable cause) { proxyListener.onResourceFailed(uri, cause); }
        })
        , ctx.asyncAssertSuccess());
    client = vertx.createHttpClient(new HttpClientOptions()
        .setDefaultPort(8080)
        .setKeepAlive(true)
        .setPipelining(true)
        .setMaxPoolSize(1));
  }

  @After
  public void after(TestContext ctx) {
    vertx.close(ctx.asyncAssertSuccess());
  }

  @Test
  public void testCreateRepo(TestContext ctx) {
    Async async = ctx.async();
    Buffer buffer = Buffer.buffer("the_resource");
    client.put("/foo", resp -> {
      ctx.assertEquals(201, resp.statusCode());
      ctx.assertEquals(1, repoMap.size());
      Map<String, Resource> repo = repoMap.get("test-1000");
      ctx.assertNotNull(repo);
      waitUntil(() -> repo.containsKey("foo"), ctx.asyncAssertSuccess(v -> {
        Resource resource = repo.get("foo");
        ctx.assertEquals(1, resource.versions.size());
        ctx.assertEquals(buffer, resource.versions.get(0));
        async.complete();
      }));
    }).end(buffer);
  }

  @Test
  public void testReupload(TestContext ctx) {
    Async async = ctx.async();
    client.put("/foo", resp1 -> {
      ctx.assertEquals(201, resp1.statusCode());
      Map<String, Resource> repo = repoMap.get("test-1000");
      ctx.assertNotNull(repo);
      waitUntil(() -> repo.containsKey("foo"), ctx.asyncAssertSuccess(v1 -> {
        client.put("/foo", resp2 -> {
          waitUntil(() -> repo.get("foo").versions.size() == 2, ctx.asyncAssertSuccess(v2 -> {
            Resource resource = repo.get("foo");
            ctx.assertEquals(Arrays.asList(Buffer.buffer("the_resource_1"), Buffer.buffer("the_resource_2")), resource.versions);
            async.complete();
          }));
        }).end(Buffer.buffer("the_resource_2"));
      }));
    }).end(Buffer.buffer("the_resource_1"));
  }

  @Test
  public void testReuploadRace(TestContext ctx) {
    Async async = ctx.async();
    int times = 10;
    for (int i = 0;i < times;i++) {
      client.put("/foo", resp1 -> {
      }).end(Buffer.buffer("the_resource_" + times));
    }
    waitUntil(() -> repoMap.containsKey("test-1000")
            && repoMap.get("test-1000").containsKey("foo")
            && repoMap.get("test-1000").get("foo").versions.size() > 0
            && repoMap.get("test-1000").get("foo").versions.get(repoMap.get("test-1000").get("foo").versions.size() - 1).toString().equals("the_resource_" + 10),
        ctx.asyncAssertSuccess(v -> {
          client.getNow("/foo", resp -> {
            resp.bodyHandler(body -> {
              ctx.assertEquals(Buffer.buffer("the_resource_10"), body);
              async.complete();
            });
          });
    }));
  }

  @Test
  public void testUploadRetry(TestContext ctx) {
    int times = 30;
    repoListener = new Repo.Listener() {
      int count = 0;
      @Override
      public boolean handlePut(String uri, Buffer content) {
        if (++count < times) {
          return false;
        } else {
          return true;
        }
      }
    };
    AtomicInteger createCount = new AtomicInteger();
    AtomicInteger failedCount = new AtomicInteger();
    AtomicInteger succeededCount = new AtomicInteger();
    proxyListener = new Proxy.Listener() {
      @Override
      public void onResourceCreate(String uri) {
        createCount.incrementAndGet();
      }
      @Override
      public void onResourceFailed(String uri, Throwable cause) {
        failedCount.incrementAndGet();
      }
      @Override
      public void onResourceSucceeded(String uri) {
        succeededCount.incrementAndGet();
      }
    };
    Async async = ctx.async();
    client.put("/foo", resp1 -> {
      ctx.assertEquals(201, resp1.statusCode());
      Map<String, Resource> repo = repoMap.get("test-1000");
      ctx.assertNotNull(repo);
      waitUntil(() -> repo.containsKey("foo"), ctx.asyncAssertSuccess(v1 -> {
        Resource resource = repo.get("foo");
        ctx.assertEquals(Collections.singletonList(Buffer.buffer("the_resource")), resource.versions);
        ctx.assertEquals(times, createCount.get());
        ctx.assertEquals(times - 1, failedCount.get());
        ctx.assertEquals(1, succeededCount.get());
        async.complete();
      }));
    }).end(Buffer.buffer("the_resource"));
  }
}
