package Server;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import Protocol.Protocol;

/*
 * Servidor TCP de transferência de arquivos com suporte a múltiplos clientes simultâneos.
 *
 * Opera em uma porta fixa, aguardando conexões em loop contínuo. A cada cliente aceito,
 * cria uma thread dedicada (ClientHandler) que assume toda a comunicação, liberando o
 * loop principal para aceitar o próximo cliente imediatamente.
 *
 * A lista de handlers ativos usa CopyOnWriteArrayList: múltiplas threads podem adicionar
 * ou remover clientes ao mesmo tempo em que outra itera a lista (ex.: para um broadcast)
 * sem risco de ConcurrentModificationException, pois a iteração opera sobre um snapshot
 * imutável do momento em que foi iniciada.
*/

public class Server {

    private static final int PORT = Protocol.PORT; // Porta onde o servidor escuta por conexões TCP

    // Lista global de todos os ClientHandlers ativos — thread-safe por CopyOnWriteArrayList:
    private final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;   // Socket do servidor 
    private volatile boolean running;    // Flag de controle do loop principal
    private final ServerChat serverChat; // Módulo de mensagens

    public Server() throws IOException {
        this.serverSocket = new ServerSocket(PORT); // Vincula o socket à porta e começa a escutar
        this.running = false;
        this.serverChat = new ServerChat(activeClients); // Inicializa o módulo de chat com referência à lista de clientes ativos
    }

    // Garante que o diretório de arquivos existe antes de iniciar o servidor:
    private void ensureFilesDir() {
        File dir = new File(Protocol.FILES_DIR);
        if (!dir.exists()) {
            dir.mkdirs(); // Cria o diretório (e intermediários) caso ainda não exista
            System.out.println("[Servidor] Diretório de arquivos criado: " + Protocol.FILES_DIR);
        }
    }

    // Loop principal - aceita conexões e delega cada cliente a uma nova thread:
    public void start() {
        running = true;
        ensureFilesDir();

        System.out.println("[Servidor] Escutando na porta " + PORT + "...");
        System.out.println("[Servidor] Servindo arquivos de: " + new File(Protocol.FILES_DIR).getAbsolutePath());

        // Thread de notificações periódicas — executa em paralelo ao loop de conexões:
        Thread periodicBroadcast = new Thread(() -> {
            int tickCount = 0;
            while (running) {
                try {
                    Thread.sleep(60_000); // Aguarda 1 minuto entre cada rodada

                    // Alterna entre as duas mensagens periódicas a cada ciclo:
                    if (tickCount % 2 == 0) {
                        serverChat.broadcastPeriodicAlive();
                    } else {
                        serverChat.broadcastPeriodicSecurity();
                    }
                    tickCount++;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restaura o flag de interrupção
                    break;
                }
            }
        });
        periodicBroadcast.setDaemon(true); // Encerra junto com a JVM sem precisar de stop() explícito
        periodicBroadcast.start();

        while (running) {
            try {
                // Bloqueia até um cliente se conectar. Retorna um socket dedicado àquele cliente:
                Socket clientSocket = serverSocket.accept();

                // Cria o handler — streams inicializados no construtor para evitar null em broadcasts imediatos:
                ClientHandler handler = new ClientHandler(clientSocket, activeClients, serverChat);
                activeClients.add(handler);

                // Inicia a thread do handler:
                Thread thread = new Thread(handler);
                thread.setDaemon(true); // Thread daemon: encerra automaticamente quando a JVM sai
                thread.start();

                System.out.println("[Servidor] Nova conexão aceita. Clientes ativos: " + activeClients.size());
                serverChat.broadcastClientConnected();

            } catch (IOException e) {
                if (running) {
                    System.err.println("[Servidor] Erro ao aceitar conexão: " + e.getMessage());
                }
            }
        }
    }

    // Encerra o servidor fechando o socket, o que fará accept() lançar uma exceção e sair do loop:
    public void stop() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("[Servidor] Erro ao encerrar servidor: " + e.getMessage());
        }
        System.out.println("[Servidor] Encerrado.");
    }

    public static void main(String[] args) throws IOException {
        Server server = new Server();
        server.start();
    }
}