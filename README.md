## A plugin for releasing large projects with Maven peacefully

### Proxy

An HTTP proxy for Nexus that speed up deployment time by a 100-factor employing basic well known performance tricks:

- single write principle
- aggressive pipelining
- multiple connections
- near caching

Usage:

```
# anywhere
> mvn com.julienviet:releaser-maven-plugin:1.0-SNAPSHOT:proxy -DstagingProfileId=$NEXUS_PROFILE -DstagingUsername=$NEXUS_USERNAME -DstagingPassword=$NEXUS_PASSWORD
Proxy started, you can deploy to http://localhost:8080
```

You can then deploy to this repository instead of Nexus, the proxy will handle all the uploads quickly and efficiently
upload them to Nexus in the background:

```
# in your project
> mvn deploy -DaltDeploymentRepository=local::default::http://localhost:8080/ 
```

The proxy will log the activity on the console:

```
Creating staging repo for 198f9fdcad2785
Created staging repo iovertx-3729 for 198f9fdcad2785
In progress 1
In progress 2
In progress 3
...
```

### Apply mojo

Mass version change

Not documented (yet)

### Commit mojo

Mass commit

Not documented (yet)

### Tag mojo

Mass tagging

Not documented (yet)