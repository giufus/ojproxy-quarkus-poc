package com.example.product;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusIntegrationTest
class ProductResourceIT {

    @Test
    void crudRoundTrip() {
        long nonce = System.currentTimeMillis();
        String sku = "IT-" + nonce;

        Integer id = given().contentType(ContentType.JSON)
            .body("{\"name\":\"WidgetIT\",\"sku\":\"" + sku + "\",\"priceCents\":1999}")
            .when().post("/products")
            .then().statusCode(201)
                   .body("id", notNullValue())
                   .body("sku", equalTo(sku))
                   .body("priceCents", equalTo(1999))
            .extract().path("id");

        given().when().get("/products/" + id)
            .then().statusCode(200).body("sku", equalTo(sku));

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"WidgetIT\",\"sku\":\"" + sku + "\",\"priceCents\":2099}")
            .when().put("/products/" + id)
            .then().statusCode(200).body("priceCents", equalTo(2099));

        given().when().delete("/products/" + id)
            .then().statusCode(204);

        given().when().get("/products/" + id)
            .then().statusCode(404);
    }

    @Test
    void duplicateSkuReturns409() {
        long nonce = System.currentTimeMillis();
        String sku = "DUP-" + nonce;

        Integer id = given().contentType(ContentType.JSON)
            .body("{\"name\":\"A\",\"sku\":\"" + sku + "\",\"priceCents\":100}")
            .when().post("/products")
            .then().statusCode(201)
            .extract().path("id");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"B\",\"sku\":\"" + sku + "\",\"priceCents\":200}")
            .when().post("/products")
            .then().statusCode(409);

        given().when().delete("/products/" + id).then().statusCode(204);
    }
}
