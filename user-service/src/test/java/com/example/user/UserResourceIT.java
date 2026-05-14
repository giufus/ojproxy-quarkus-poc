package com.example.user;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusIntegrationTest
class UserResourceIT {

    @Test
    void crudRoundTrip() {
        String name = "it-user-" + System.currentTimeMillis();
        String email = name + "@example.com";

        // CREATE
        Integer id = given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}")
            .when().post("/users")
            .then().statusCode(201)
                   .body("id", notNullValue())
                   .body("name", equalTo(name))
                   .body("email", equalTo(email))
            .extract().path("id");

        // READ
        given().when().get("/users/" + id)
            .then().statusCode(200).body("name", equalTo(name));

        // UPDATE
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "-updated\",\"email\":\"" + email + "\"}")
            .when().put("/users/" + id)
            .then().statusCode(200).body("name", equalTo(name + "-updated"));

        // DELETE
        given().when().delete("/users/" + id)
            .then().statusCode(204);

        // 404 after delete
        given().when().get("/users/" + id)
            .then().statusCode(404);
    }
}
