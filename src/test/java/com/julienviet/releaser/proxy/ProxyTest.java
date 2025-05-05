package com.julienviet.releaser.proxy;

import com.julienviet.releaser.Proxy;
import com.julienviet.releaser.ProxyOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import junit.framework.AssertionFailedError;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
      Listener DEFAULT = new Listener() {
      };

      default boolean handlePut(String uri, Buffer content) {
        return true;
      }
    }
  }

  private void waitUntil(BooleanSupplier condition) {
    waitUntil(0, condition);
  }

  private void waitUntil(int times, BooleanSupplier condition) {
    while (!condition.getAsBoolean()) {
      if (times++ >= 1000) {
        AssertionFailedError afe = new AssertionFailedError();
        afe.initCause(new TimeoutException());
        throw afe;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        AssertionFailedError afe = new AssertionFailedError();
        afe.initCause(new TimeoutException());
      }
    }
  }

  private static <T> T await(Future<T> fut) {
    try {
      return fut.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      AssertionFailedError afe = new AssertionFailedError();
      afe.initCause(e);
      throw afe;
    } catch (Exception e) {
      AssertionFailedError afe = new AssertionFailedError();
      afe.initCause(e);
      throw afe;
    }
  }

  @Before
  public void before() {
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
      });
    await(stagingServer.listen(8081));
    Future<String> deployment = vertx.deployVerticle(new Proxy(new ProxyOptions()
      .setStagingProfileId("my_profile")
      .setStagingHost("localhost")
      .setStagingPort(8081)
      .setStagingSsl(false)
      .setStagingKeepAlive(true)
      .setStagingPipelining(true)
      .setStagingMaxPoolSize(1)
      .setPort(8080), new Proxy.Listener() {
      public void onStagingCreate(String profileId) {
        proxyListener.onStagingCreate(profileId);
      }

      public void onStagingSucceded(String profileId, String repoId) {
        proxyListener.onStagingSucceded(profileId, repoId);
      }

      public void onStagingFailed(String profileId, Throwable cause) {
        proxyListener.onStagingFailed(profileId, cause);
      }

      public void onResourceCreate(String uri) {
        proxyListener.onResourceCreate(uri);
      }

      public void onResourceSucceeded(String uri) {
        proxyListener.onResourceSucceeded(uri);
      }

      public void onResourceFailed(String uri, Throwable cause) {
        proxyListener.onResourceFailed(uri, cause);
      }
    }));
    await(deployment);
    client = vertx.createHttpClient(new HttpClientOptions()
      .setDefaultPort(8080)
      .setKeepAlive(true)
      .setPipelining(true), new PoolOptions().setHttp1MaxSize(1));
  }

  @After
  public void after() {
    await(vertx.close());
  }

  private void put(String uri, Buffer content) {
    Future<Buffer> res = client.request(HttpMethod.PUT, uri)
      .compose(request -> request
        .send(content)
        .expecting(HttpResponseExpectation.SC_CREATED)
        .compose(HttpClientResponse::body));
    await(res);
  }

  private Buffer get(String uri) {
    Future<Buffer> res = client.request(HttpMethod.GET, uri)
      .compose(request -> request
        .send()
        .expecting(HttpResponseExpectation.SC_OK)
        .compose(HttpClientResponse::body));
    return await(res);
  }

  @Test
  public void testCreateRepo() {
    Buffer buffer = Buffer.buffer("the_resource");
    put("/foo", buffer);
    assertEquals(1, repoMap.size());
    Map<String, Resource> repo = repoMap.get("test-1000");
    assertNotNull(repo);
    Resource resource = repo.get("foo");
    assertEquals(1, resource.versions.size());
    assertEquals(buffer, resource.versions.get(0));
  }

  @Test
  public void testReupload() {
    put("/foo", Buffer.buffer("the_resource_1"));
    Map<String, Resource> repo = repoMap.get("test-1000");
    assertNotNull(repo);
    waitUntil(() -> repo.containsKey("foo"));
    put("/foo", Buffer.buffer("the_resource_2"));
    waitUntil(() -> repo.get("foo").versions.size() == 2);
    Resource resource = repo.get("foo");
    assertEquals(Arrays.asList(Buffer.buffer("the_resource_1"), Buffer.buffer("the_resource_2")), resource.versions);
  }

  @Test
  public void testReuploadRace() {
    int times = 10;
    for (int i = 0; i < times; i++) {
      put("/foo", Buffer.buffer("the_resource_" + times));
    }
    waitUntil(() -> repoMap.containsKey("test-1000")
      && repoMap.get("test-1000").containsKey("foo")
      && repoMap.get("test-1000").get("foo").versions.size() > 0
      && repoMap.get("test-1000").get("foo").versions.get(repoMap.get("test-1000").get("foo").versions.size() - 1).toString().equals("the_resource_" + 10));
    Buffer body = get("/foo");
    assertEquals(Buffer.buffer("the_resource_10"), body);
  }

  @Test
  public void testUploadRetry() {
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
    put("/foo", Buffer.buffer("the_resource"));
    Map<String, Resource> repo = repoMap.get("test-1000");
    assertNotNull(repo);
    waitUntil(() -> repo.containsKey("foo"));
    Resource resource = repo.get("foo");
    assertEquals(Collections.singletonList(Buffer.buffer("the_resource")), resource.versions);
    assertEquals(times, createCount.get());
    assertEquals(times - 1, failedCount.get());
    assertEquals(1, succeededCount.get());
  }
}
