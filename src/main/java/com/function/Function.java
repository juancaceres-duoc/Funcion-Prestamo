package com.function;

import com.function.util.DBConnection;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.net.*;
import java.io.*;

import org.json.JSONObject;

public class Function {
        @FunctionName("prestamos")
        public HttpResponseMessage run(
                        @HttpTrigger(name = "req", methods = { HttpMethod.GET, HttpMethod.POST,
                                        HttpMethod.DELETE }, authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
                        final ExecutionContext context) {

                try {
                        Connection conn = DBConnection.getConnection();

                        // ===================== GET =====================
                        if (request.getHttpMethod() == HttpMethod.GET) {

                                String sql = "SELECT p.ID_PRESTAMO, u.NOMBRE AS USUARIO, l.TITULO AS LIBRO, p.FECHA_PRESTAMO "
                                                +
                                                "FROM PRESTAMOS p " +
                                                "JOIN USUARIOS u ON p.ID_USUARIO = u.ID_USUARIO " +
                                                "JOIN LIBROS l ON p.ID_LIBRO = l.ID_LIBRO";

                                Statement stmt = conn.createStatement();
                                ResultSet rs = stmt.executeQuery(sql);

                                List<Map<String, Object>> prestamos = new ArrayList<>();

                                while (rs.next()) {
                                        Map<String, Object> p = new HashMap<>();
                                        p.put("idPrestamo", rs.getInt("ID_PRESTAMO"));
                                        p.put("usuario", rs.getString("USUARIO"));
                                        p.put("libro", rs.getString("LIBRO"));
                                        p.put("fecha", rs.getString("FECHA_PRESTAMO"));
                                        prestamos.add(p);
                                }

                                return request.createResponseBuilder(HttpStatus.OK)
                                                .header("Content-Type", "application/json")
                                                .body(prestamos)
                                                .build();
                        }

                        // ===================== POST =====================
                        if (request.getHttpMethod() == HttpMethod.POST) {

                                String body = request.getBody().orElse("");
                                JSONObject json = new JSONObject(body);

                                int idUsuario = json.getInt("idUsuario");
                                int idLibro = json.getInt("idLibro");

                                // validar disponibilidad
                                PreparedStatement check = conn.prepareStatement(
                                                "SELECT DISPONIBLE FROM LIBROS WHERE ID_LIBRO = ?");
                                check.setInt(1, idLibro);
                                ResultSet rs = check.executeQuery();

                                if (rs.next() && "N".equals(rs.getString("DISPONIBLE"))) {
                                        return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                                                        .header("Content-Type", "application/json")
                                                        .body("Libro no disponible")
                                                        .build();
                                }

                                // insertar préstamo
                                PreparedStatement ps = conn.prepareStatement(
                                                "INSERT INTO PRESTAMOS (ID_USUARIO, ID_LIBRO) VALUES (?, ?)");
                                ps.setInt(1, idUsuario);
                                ps.setInt(2, idLibro);
                                ps.executeUpdate();

                                // actualizar libro
                                PreparedStatement update = conn.prepareStatement(
                                                "UPDATE LIBROS SET DISPONIBLE = 'N' WHERE ID_LIBRO = ?");
                                update.setInt(1, idLibro);
                                update.executeUpdate();

                                try {
                                        enviarEventoPrestamo(conn, idUsuario, idLibro);
                                } catch (Exception e) {
                                        context.getLogger().warning("Error enviando evento: " + e.getMessage());
                                }

                                return request.createResponseBuilder(HttpStatus.OK)
                                                .header("Content-Type", "application/json")
                                                .body("Prestamo creado")
                                                .build();
                        }

                        // ===================== DELETE =====================
                        if (request.getHttpMethod() == HttpMethod.DELETE) {

                                String id = request.getQueryParameters().get("id");

                                if (id == null) {
                                        return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                                                        .header("Content-Type", "application/json")
                                                        .body("Falta id")
                                                        .build();
                                }

                                int idPrestamo = Integer.parseInt(id);

                                PreparedStatement get = conn.prepareStatement(
                                                "SELECT ID_LIBRO FROM PRESTAMOS WHERE ID_PRESTAMO = ?");
                                get.setInt(1, idPrestamo);
                                ResultSet rs = get.executeQuery();

                                if (!rs.next()) {
                                        return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                                                        .header("Content-Type", "application/json")
                                                        .body("Prestamo no existe")
                                                        .build();
                                }

                                int idLibro = rs.getInt("ID_LIBRO");

                                // eliminar préstamo
                                PreparedStatement delete = conn.prepareStatement(
                                                "DELETE FROM PRESTAMOS WHERE ID_PRESTAMO = ?");
                                delete.setInt(1, idPrestamo);
                                int deleted = delete.executeUpdate();

                                if (deleted == 0) {
                                        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .header("Content-Type", "application/json")
                                                        .body("No se pudo eliminar el prestamo")
                                                        .build();
                                }

                                // actualizar libro
                                PreparedStatement update = conn.prepareStatement(
                                                "UPDATE LIBROS SET DISPONIBLE = 'S' WHERE ID_LIBRO = ?");
                                update.setInt(1, idLibro);
                                update.executeUpdate();

                                return request.createResponseBuilder(HttpStatus.OK)
                                                .header("Content-Type", "application/json")
                                                .body("Libro devuelto")
                                                .build();
                        }

                } catch (Exception e) {
                        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .header("Content-Type", "application/json")
                                        .body(e.getMessage())
                                        .build();
                }

                return request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                                .header("Content-Type", "application/json")
                                .build();
        }

        // ===================== EVENT GRID =====================
        private void enviarEventoPrestamo(Connection conn, int idUsuario, int idLibro) throws Exception {

                String endpoint = System.getenv("EVENT_GRID_ENDPOINT");
                String key = System.getenv("EVENT_GRID_KEY");

                // Obtener datos reales
                PreparedStatement ps = conn.prepareStatement(
                                "SELECT u.NOMBRE, u.EMAIL, l.TITULO " +
                                                "FROM USUARIOS u, LIBROS l " +
                                                "WHERE u.ID_USUARIO = ? AND l.ID_LIBRO = ?");

                ps.setInt(1, idUsuario);
                ps.setInt(2, idLibro);

                ResultSet rs = ps.executeQuery();
                if (!rs.next())
                        return;

                String nombre = rs.getString("NOMBRE");
                String email = rs.getString("EMAIL");
                String libro = rs.getString("TITULO");

                // 📅 Fechas
                LocalDate fechaPrestamo = LocalDate.now();
                LocalDate fechaDevolucion = fechaPrestamo.plusDays(7);

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

                String event = """
                                [
                                  {
                                    "id": "%s",
                                    "eventType": "PrestamoCreado",
                                    "subject": "prestamo",
                                    "eventTime": "%s",
                                    "data": {
                                      "email": "%s",
                                      "nombre": "%s",
                                      "libro": "%s",
                                      "fechaPrestamo": "%s",
                                      "fechaDevolucion": "%s"
                                    },
                                    "dataVersion": "1.0"
                                  }
                                ]
                                """.formatted(
                                UUID.randomUUID(),
                                java.time.Instant.now().toString(),
                                email,
                                nombre,
                                libro,
                                fechaPrestamo.format(formatter),
                                fechaDevolucion.format(formatter));

                URL url = new URL(endpoint);
                HttpURLConnection connHttp = (HttpURLConnection) url.openConnection();

                connHttp.setRequestMethod("POST");
                connHttp.setRequestProperty("aeg-sas-key", key);
                connHttp.setRequestProperty("Content-Type", "application/json");
                connHttp.setDoOutput(true);

                try (OutputStream os = connHttp.getOutputStream()) {
                        os.write(event.getBytes());
                }

                connHttp.getResponseCode();
        }
}