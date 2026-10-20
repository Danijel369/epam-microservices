package com.epam.microservices.resource.exception;

import com.epam.microservices.resource.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidFileFormatException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFileFormat(InvalidFileFormatException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(InvalidMp3Exception.class)
    public ResponseEntity<ErrorResponse> handleInvalidMp3(InvalidMp3Exception ex) {
        return badRequest(ex.getMessage());
    }

    /**
     * An unreadable/empty request body (e.g. a {@code POST /resources} with no
     * bytes) never reaches the controller; map it to a clear 400 rather than 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return badRequest("Invalid MP3 file: request body is empty");
    }

    @ExceptionHandler(InvalidIdException.class)
    public ResponseEntity<ErrorResponse> handleInvalidId(InvalidIdException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(InvalidCsvTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCsvToken(InvalidCsvTokenException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(CsvTooLongException.class)
    public ResponseEntity<ErrorResponse> handleCsvTooLong(CsvTooLongException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return json(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(ex.getMessage(), String.valueOf(HttpStatus.NOT_FOUND.value())));
    }

    /**
     * Passes an error from the Song Service through unchanged: same status, same JSON body.
     */
    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<String> handleDownstream(DownstreamException ex) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ex.getBody());
    }

    /**
     * A known path hit with the wrong HTTP verb (e.g. {@code GET /resources}) is a
     * client mistake, not a server fault — answer 405 instead of 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return json(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("Method " + ex.getMethod() + " is not supported for this endpoint",
                        String.valueOf(HttpStatus.METHOD_NOT_ALLOWED.value())));
    }

    /**
     * A required query parameter left off (e.g. {@code DELETE /resources} with no
     * {@code ?id}) is a bad request, not a server fault.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("Required request parameter '" + ex.getParameterName() + "' is missing");
    }

    /**
     * An unknown path (no handler / no static resource) is a 404, not a 500.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleUnknownPath(Exception ex) {
        return json(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("Endpoint not found",
                        String.valueOf(HttpStatus.NOT_FOUND.value())));
    }

    /**
     * The client's {@code Accept} header excludes every representation we can
     * produce — that is a 406, not a server fault.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return json(HttpStatus.NOT_ACCEPTABLE)
                .body(ErrorResponse.of("Requested media type is not acceptable",
                        String.valueOf(HttpStatus.NOT_ACCEPTABLE.value())));
    }

    /**
     * A failure in the object storage is a server-side fault — answer 500 with
     * the standard error body and log the underlying cause.
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException ex) {
        log.error("Storage operation failed", ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("Internal server error",
                        String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("Internal server error",
                        String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value())));
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        return json(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(message, String.valueOf(HttpStatus.BAD_REQUEST.value())));
    }

    private ResponseEntity.BodyBuilder json(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }
}
