import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/*
 * Servidor HTTP multithreaded.
 *
 * Opera em uma porta fixa, aguardando conexões em loop contínuo. A cada conexão
 * aceita, cria uma thread dedicada (ClientHandler) que assume o processamento
 * da requisição HTTP, liberando o loop principal para aceitar a próxima conexão
 * imediatamente.
 *
 * A lista de handlers ativos usa CopyOnWriteArrayList: inserções e remoções
 * feitas por threads distintas não interferem em iterações concorrentes,
 * eliminando ConcurrentModificationException sem necessidade de locks explícitos.
*/

public class Server {

    // Lista global de todos os ClientHandlers ativos — thread-safe por CopyOnWriteArrayList:
    private final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();

    private static final int PORT = 8080; // Porta HTTP 
    private ServerSocket serverSocket;    // Socket do servidor 
    private volatile boolean running;     // Flag de controle do loop principal, lida por múltiplas threads

    public Server() throws IOException {
        this.serverSocket = new ServerSocket(PORT); // Vincula o socket à porta e começa a escutar
        this.running = false;
    }

    // Loop principal: aceita conexões e delega cada uma a uma nova thread:
    public void start() {
        running = true;

        System.out.println("[HTTP] Servidor iniciado na porta " + PORT);
        System.out.println("[HTTP] Acesse: http://localhost:" + PORT + "/");

        while (running) {
            try {
                // Bloqueia até um browser se conectar; retorna um socket dedicado àquela conexão:
                Socket clientSocket = serverSocket.accept();

                ClientHandler handler = new ClientHandler(clientSocket, activeClients);
                activeClients.add(handler);

                // Inicia a thread do handler; o loop principal volta ao accept() imediatamente:
                Thread thread = new Thread(handler);
                thread.setDaemon(true); // Thread daemon: encerra automaticamente quando a JVM sai
                thread.start();

                System.out.println("[HTTP] Conexão aceita. Ativas: " + activeClients.size());

            } catch (IOException e) {
                if (running) {
                    System.err.println("[HTTP] Erro ao aceitar conexão: " + e.getMessage());
                }
            }
        }
    }

    // Encerra o servidor fechando o socket, o que fará accept() lançar exceção e sair do loop:
    public void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("[HTTP] Erro ao encerrar servidor: " + e.getMessage());
        }
        System.out.println("[HTTP] Servidor encerrado.");
    }

    public static void main(String[] args) throws IOException {
        Server server = new Server();
        server.start();
    }
}
