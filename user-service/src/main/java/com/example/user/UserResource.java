package com.example.user;

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

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    public record CreateUser(String name, String email) {}
    public record UpdateUser(String name, String email) {}

    @GET
    public List<User> list() {
        return User.listAll(Sort.by("id"));
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") Long id) {
        User u = User.findById(id);
        return u == null ? Response.status(Response.Status.NOT_FOUND).build()
                         : Response.ok(u).build();
    }

    @POST
    @Transactional
    public Response create(CreateUser body, @Context UriInfo uri) {
        User u = new User();
        u.name = body.name();
        u.email = body.email();
        u.persist();
        URI location = uri.getAbsolutePathBuilder().path(String.valueOf(u.id)).build();
        return Response.created(location).entity(u).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response update(@PathParam("id") Long id, UpdateUser body) {
        User u = User.findById(id);
        if (u == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (body.name() != null) u.name = body.name();
        if (body.email() != null) u.email = body.email();
        return Response.ok(u).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = User.deleteById(id);
        return Response.status(deleted ? Response.Status.NO_CONTENT
                                       : Response.Status.NOT_FOUND).build();
    }
}
