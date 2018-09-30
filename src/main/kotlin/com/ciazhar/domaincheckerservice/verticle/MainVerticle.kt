package com.ciazhar.domaincheckerservice.verticle


import com.ciazhar.domaincheckerservice.extension.logger
import com.ciazhar.domaincheckerservice.extension.param
import com.ciazhar.domaincheckerservice.extension.single
import com.ciazhar.domaincheckerservice.model.Dnsbl
import com.google.common.io.Resources
import com.google.gson.Gson
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpServer
import io.vertx.core.json.Json
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.util.stream.Collectors


class MainVerticle (private var Mongo : MongoClient): AbstractVerticle() {

    private val log = logger(MainVerticle::class)

    private val config by lazy { config() }

    override fun start(startFuture: Future<Void>) {
        println("Initialize Main Verticle...")

        println("Initialize Router...")
        val router = Router.router(vertx)

        // Bind "/" to our hello message.
        router.route("/").handler { routingContext ->
            val response = routingContext.response()
            response
                    .putHeader("content-type", "text/html")
                    .end("<h1>Hello from my first Vert.x 3 application</h1>")
        }

        router.route("/assets/*").handler(StaticHandler.create("assets"))

        router.route("/api/dnsbl*").handler(BodyHandler.create())
        router.post("/api/dnsbl").handler(this::addOne)
        router.get("/api/dnsbl").handler(this::readJsonFile)
        router.get("/api/dnsbl/:id").handler(this::getOne)
        router.put("/api/dnsbl").handler(this::updateOne)
        router.delete("/api/dnsbl/:id").handler(this::deleteOne)

        println("Starting HttpServer...")
        val httpServer = single<HttpServer> { it ->
            vertx.createHttpServer()
                    .requestHandler { router.accept(it) }
                    .listen(config.getInteger("HTTP_PORT"), it)
        }

        httpServer.subscribe(
                {
                    println("HttpServer started in port ${config.getInteger("HTTP_PORT")}")
                    println("Main Verticle Deployed!")
                    startFuture.complete()
                },
                {
                    log.error("Failed to start HttpServer. [${it.message}]", it)
                    log.error("Main Verticle Failed to Deploy!")
                    startFuture.fail(it)
                }
        )
    }
    var resource = Resources.getResource("dnsbl.json")
    var dnsblListJson = resource.file

    private fun readJsonFile(routingContext: RoutingContext) {
        val gson = Gson()
        val bufferedReader: BufferedReader = File(dnsblListJson).bufferedReader()
        val inputString = bufferedReader.use { it.readText() }

        val dnsbl = gson.fromJson(inputString, Array<Dnsbl>::class.java)

        routingContext.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(Json.encodePrettily(dnsbl))
    }

    private fun addOne(routingContext: RoutingContext) {
        val dnsbl = Json.decodeValue(routingContext.bodyAsString,
                    Dnsbl::class.java)

        Mongo.insert(dnsblCollectionName, dnsbl?.toJson()) {res ->
            dnsbl.id=res.result()
            routingContext.response()
                    .setStatusCode(201)
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily(dnsbl))
        }
    }

    private var dnsblCollectionName = "dnsbl"

    private fun getAll(routingContext : RoutingContext ) {
        Mongo.find(dnsblCollectionName, JsonObject()) {res ->
            val objects = res.result()
            val whiskies = objects.stream().map{ Dnsbl(it) }.collect(Collectors.toList())

            routingContext.response()
                    .setStatusCode(201)
                    .putHeader("content-type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily(whiskies))
        }
    }

    private fun getOne(routingContext : RoutingContext ) {
        val id = routingContext.param("id")
        if (id ==null){
            routingContext.response().setStatusCode(400).end()
        }
        Mongo.find(dnsblCollectionName, JsonObject().put("_id", id)) { res ->
            if (res.succeeded()) {
                if (res.result() == null) {
                    routingContext.response().setStatusCode(404).end()
                }
                print("size"+res.result().size)
                if (res.result().size>0){
                    val dnsbl = Dnsbl(res.result()[0])
                    routingContext.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .end(Json.encodePrettily(dnsbl))
                }else{
                    routingContext.response()
                            .setStatusCode(404)
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .end(Json.encodePrettily("id not found"))
                }

            } else {
                routingContext.response().setStatusCode(404).end()
            }
        }
    }

    private fun updateOne(routingContext: RoutingContext) {
        val json = routingContext.bodyAsJson
        if (json == null) {
            routingContext.response().setStatusCode(400).end()
        } else {
            Mongo.update(dnsblCollectionName,
                    JsonObject().put("_id", json.getString("id")), // Select a unique document
                    // The update syntax: {$set, the json object containing the fields to update}
                    JsonObject()
                            .put("\$set", json)
            ) { v ->
                if (v.failed()) {
                    routingContext.response().setStatusCode(404).end()
                } else {
                    val dnsbl = Dnsbl(json)
                    dnsbl.id=json.getString("id")
                    routingContext.response()
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .end(Json.encodePrettily(dnsbl))
                }
            }
        }
    }

    private fun deleteOne(routingContext: RoutingContext) {
        val id = routingContext.request().getParam("id")
        if (id == null) {
            routingContext.response().setStatusCode(400).end()
        } else {
            Mongo.removeOne(dnsblCollectionName, JsonObject().put("_id", id)
            ) { routingContext.response().setStatusCode(204).end() }
        }
    }
}
