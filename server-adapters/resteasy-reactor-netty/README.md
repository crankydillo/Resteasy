# Introduction

A server-adapter built on top of
[reactor-netty](https://github.com/reactor/reactor-netty).  See the javadoc for
detailed information.

# Running

To start a port with some default resources loaded, you run:

```
mvn -Prun
```

and then hit it with `curl`:

```
curl http://localhost:8081/simple
```

