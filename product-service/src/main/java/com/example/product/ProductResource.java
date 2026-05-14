package com.example.product;

import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductResource {

    public record CreateProduct(String name, String sku, Integer priceCents) {}
    public record UpdateProduct(String name, String sku, Integer priceCents) {}

    @GET
    public List<Product> list() {
        return Product.listAll(Sort.by("id"));
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        Product p = Product.findById(id);
        return p == null ? Response.status(Response.Status.NOT_FOUND).build()
                         : Response.ok(p).build();
    }

    @POST
    @Transactional
    public Response create(CreateProduct body, @Context UriInfo uri) {
        if (Product.find("sku", body.sku()).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "sku already exists", "sku", body.sku()))
                .build();
        }
        Product p = new Product();
        p.name = body.name();
        p.sku = body.sku();
        p.priceCents = body.priceCents() == null ? 0 : body.priceCents();
        p.persist();
        URI location = uri.getAbsolutePathBuilder().path(String.valueOf(p.id)).build();
        return Response.created(location).entity(p).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") Long id, UpdateProduct body) {
        Product p = Product.findById(id);
        if (p == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (body.name() != null) p.name = body.name();
        if (body.sku() != null) p.sku = body.sku();
        if (body.priceCents() != null) p.priceCents = body.priceCents();
        return Response.ok(p).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = Product.deleteById(id);
        return Response.status(deleted ? Response.Status.NO_CONTENT
                                       : Response.Status.NOT_FOUND).build();
    }
}
