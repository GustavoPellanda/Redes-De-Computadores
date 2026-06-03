import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/*
 * Thread dedicada ao atendimento de uma única conexão HTTP.
 * Criada pelo servidor a cada conexão aceita, é responsável por:
 *   1. Ler e parsear a requisição HTTP enviada pelo browser
 *   2. Resolver o caminho solicitado para um arquivo no sistema de arquivos
 *   3. Delegar a construção e o envio da resposta ao HttpResponse
 *   4. Ao terminar, remover a si mesma da lista global de handlers ativos
*/

public class ClientHandler implements Runnable {

    private static final String FILES_DIR = "./www";       // Diretório raiz dos arquivos servidos pelo servidor
    private static final String INDEX_FILE = "index.html"; // Arquivo padrão retornado para requisições à raiz "/"

    private final Socket socket;   // Socket TCP da conexão com este browser
    private final String clientId; // Identificador IP:porta
    private final List<ClientHandler> activeClients; // Referência à lista global de handlers ativos

    public ClientHandler(Socket socket, List<ClientHandler> activeClients) {
        this.socket = socket;
        this.activeClients = activeClients;
        this.clientId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    // Função de acesso da thread: processa a requisição e garante o fechamento da conexão ao final:
    @Override
    public void run() {
        System.out.println("[HTTP] Conexão recebida de: " + clientId);

        try {
            handleRequest();
        } catch (IOException e) {
            System.out.println("[HTTP] Erro ao processar requisição de " + clientId + ": " + e.getMessage());
        } finally {
            close();
        }
    }

    // Lê a requisição HTTP e chama serveFile() para enviar o arquivo solicitado ou uma resposta de erro:
    private void handleRequest() throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );

        HttpResponse response = new HttpResponse(socket.getOutputStream());

        // Lê a linha de requisição — contém método, caminho e versão HTTP:
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isBlank()) return;

        System.out.println("[HTTP] " + clientId + " → " + requestLine);

        // Consome e descarta os cabeçalhos restantes até a linha em branco obrigatória:
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            // Cabeçalhos de requisição (Host, Accept, etc.) não são utilizados por este servidor
        }

        // Parseia os três campos da linha de requisição: MÉTODO /caminho HTTP/versão:
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            response.sendError(400, "Bad Request", "Linha de requisição HTTP malformada.");
            return;
        }

        String method = parts[0];
        String path = parts[1];

        // Apenas GET é suportado — outros métodos recebem 405 Method Not Allowed:
        if (!method.equalsIgnoreCase("GET")) {
            response.sendError(405, "Method Not Allowed", "Método não suportado: " + method);
            return;
        }

        serveFile(path, response);
    }

    // Envia o arquivo solicitado pelo caminho URL, ou uma resposta de erro se o arquivo não existir ou for inacessível:
    private void serveFile(String urlPath, HttpResponse response) throws IOException {

        // Requisições à raiz "/" retornam o arquivo index.html por convenção:
        if (urlPath.equals("/")) {
            urlPath = "/" + INDEX_FILE;
        }

        // Remove query string se presente (ex: "/pagina.html?v=2" → "/pagina.html"):
        int queryIndex = urlPath.indexOf('?');
        if (queryIndex != -1) {
            urlPath = urlPath.substring(0, queryIndex);
        }

        File rootDir = new File(FILES_DIR).getCanonicalFile(); // Diretório raiz absoluto para comparação segura
        File requestedFile = new File(rootDir, urlPath).getCanonicalFile(); // Resolve o caminho solicitado para um arquivo absoluto

        // Verifica se o arquivo solicitado está dentro do diretório raiz para prevenir path traversal:
        if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
            System.out.println("[HTTP] Path traversal bloqueado de " + clientId + ": " + urlPath);
            response.sendError(403, "Forbidden", "Acesso negado.");
            return;
        }

        if (!requestedFile.exists() || !requestedFile.isFile()) {
            System.out.println("[HTTP] Não encontrado para " + clientId + ": " + urlPath);
            response.sendError(404, "Not Found",
                "O arquivo <strong>" + urlPath + "</strong> não foi encontrado neste servidor.");
            return;
        }

        // Detecta o Content-Type pela extensão do arquivo antes de iniciar o envio:
        String contentType = MimeTypes.getContentType(requestedFile.getName());

        System.out.println("[HTTP] Enviando " + requestedFile.getName() + " (" + requestedFile.length() + " bytes, " + contentType + ") para " + clientId);

        response.sendFile(requestedFile, contentType);

        System.out.println("[HTTP] " + requestedFile.getName() + " → " + clientId + " [200 OK]");
    }

    // Fecha o socket e remove este handler da lista global de handlers ativos:
    private void close() {
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("[HTTP] Erro ao fechar socket de " + clientId + ": " + e.getMessage());
        }

        // CopyOnWriteArrayList garante que a remoção não interfere em iterações concorrentes:
        activeClients.remove(this);
        System.out.println("[HTTP] Conexão encerrada: " + clientId + " | Ativas: " + activeClients.size());
    }
}
