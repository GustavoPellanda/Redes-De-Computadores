import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*
 * Classe utilitária responsável pela construção e envio de respostas HTTP.
 * Centraliza a serialização dos cabeçalhos e o envio do corpo, de forma que
 * ClientHandler não precise conhecer os detalhes do formato HTTP. 
*/

public class HttpResponse {

    private static final int CHUNK_SIZE = 8 * 1024; // Tamanho do buffer de leitura de arquivo (8 KB)
    private final OutputStream out; // Stream de saída do socket — recebe cabeçalhos e corpo

    public HttpResponse(OutputStream out) {
        this.out = out; // Construtor recebe a OutputStream do socket para enviar a resposta
    }

    // Envia o arquivo solicitado pelo caminho URL, ou uma resposta de erro se o arquivo não existir ou for inacessível:
    public void sendFile(File file, String contentType) throws IOException {
        byte[] headers = buildHeaders(200, "OK", contentType, file.length());
        out.write(headers);

        // Divide o arquivo em chunks para evitar carregar arquivos grandes inteiros na memória:
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        out.flush();
    }

    // Envia uma resposta de erro HTTP com um corpo HTML simples explicando o erro:
    public void sendError(int statusCode, String statusText, String bodyMessage) throws IOException {
        String html =
            "<!DOCTYPE html>" +
            "<html><head><title>" + statusCode + " " + statusText + "</title></head>" +
            "<body><h1>" + statusCode + " " + statusText + "</h1>" +
            "<p>" + bodyMessage + "</p></body></html>";

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        byte[] headers = buildHeaders(statusCode, statusText, "text/html; charset=utf-8", body.length);

        out.write(headers);
        out.write(body);
        out.flush();
    }

    // Constrói os bytes dos cabeçalhos HTTP para a resposta, incluindo status, Content-Type e Content-Length:
    private byte[] buildHeaders(int statusCode, String statusText, String contentType, long contentLength) {

        // Gera a data atual no formato RFC 1123 exigido pelo protocolo HTTP:
        String date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);

        String headers =
            "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Date: " + date + "\r\n" + 
            "Server: ServidorHTTP/1.0\r\n" +
            "Content-Type: "   + contentType   + "\r\n" +
            "Content-Length: " + contentLength + "\r\n" +
            "Connection: close\r\n" + // Encerra a conexão TCP após o envio — dispensa implementação de keep-alive
            "\r\n";                   // Linha em branco obrigatória: separa cabeçalhos do corpo

        return headers.getBytes(StandardCharsets.US_ASCII);
    }
}
