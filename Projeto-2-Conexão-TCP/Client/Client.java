package Client;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.util.concurrent.SynchronousQueue;

import Protocol.Frame;
import Protocol.Protocol;

/*
 * Cliente TCP com menu interativo, transferência de arquivos e chat bidirecional.
 *
 * Opera com duas threads:
 *
 *   Thread principal (main) — menu e entrada do usuário:
 *     Exibe o menu, lê a escolha do usuário, escreve frames no stream de saída
 *     e, quando aguarda resposta do servidor (ex.: durante um GET), bloqueia em
 *     responseQueue.take() até que StreamReader deposite o sinal correspondente.
 *
 *   Thread leitora (StreamReader) — leitura contínua do stream:
 *     Lê frames do stream de entrada em loop. Mensagens comuns são impressas no
 *     console. Metadados de arquivo e sinais de fim de transferência são
 *     depositados na responseQueue para consumo pela thread principal.
*/

public class Client {

    private final String serverHost;    // Host do servidor informado pelo usuário
    private final int serverPort;       // Porta do servidor
    private final Socket socket;        // Socket TCP do cliente
    private final DataInputStream in;   // Stream de leitura — usado exclusivamente por StreamReader
    private final DataOutputStream out; // Stream de escrita — usado exclusivamente pela thread principal
    private final Scanner scanner;      // Leitura da entrada do usuário no console

    // Canal de comunicação entre StreamReader e a thread principal — usado para coordenar o fluxo de um download:
    private final SynchronousQueue<String> responseQueue = new SynchronousQueue<>();

    public Client() throws IOException {
        this.scanner = new Scanner(System.in);

        // Solicita endereço do servidor:
        System.out.print("[Cliente] Informe o endereço IP do servidor: ");
        this.serverHost = scanner.nextLine();

        // Solicita porta do servidor:
        System.out.print("[Cliente] Informe a porta do servidor: ");
        this.serverPort = Integer.parseInt(scanner.nextLine());
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Porta inválida: " + serverPort);
        }

        // Abre a conexão TCP com o servidor e inicializa os streams:
        this.socket = new Socket(serverHost, serverPort);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    // Exibe o menu principal e retorna a opção escolhida pelo usuário:
    private String showMenu() {
        System.out.println("\n╔══════════════════════════════╗");
        System.out.println("║     SERVIDOR DE ARQUIVOS     ║");
        System.out.println("╠══════════════════════════════╣");
        System.out.println("║  1 - Baixar arquivo          ║");
        System.out.println("║  2 - Chat                    ║");
        System.out.println("║  3 - Sair                    ║");
        System.out.println("╚══════════════════════════════╝");
        System.out.print("Escolha: ");
        return scanner.nextLine().trim();
    }

    // Envia uma mensagem de texto ao servidor:
    private void sendMsg(String text) throws IOException {
        Frame.writeMsgFrame(out, text);
    }

    // Administra o processo de download de um arquivo:
    private void handleDownload(StreamReader streamReader) throws IOException {
        System.out.print("[Cliente] Nome do arquivo: ");
        String filename = scanner.nextLine().trim();

        if (filename.isEmpty()) {
            System.out.println("[Cliente] Nome do arquivo não pode ser vazio.");
            return;
        }

        sendMsg(Protocol.CMD_GET + " " + filename);

        // Aguarda StreamReader identificar a resposta do servidor:
        String signal = awaitResponse();

        if (signal.startsWith("__FILE_ERROR__")) {
            System.out.println("[Cliente] " + signal.substring("__FILE_ERROR__".length()).trim());
            return;
        }

        // Verifica se o sinal recebido contém os metadados do arquivo e inicia a transferência:
        if (signal.startsWith("__FILE_META__")) {
            // Formato: "__FILE_META__ <hash> <tamanho>"
            String[] parts = signal.substring("__FILE_META__".length()).trim().split(" ", 2);
            String expectedHash = parts[0];
            long fileSize = Long.parseLong(parts[1]);

            System.out.println("[Cliente] Servidor confirmou arquivo. Tamanho: " + fileSize + " bytes");
            System.out.println("[Cliente] Hash esperado: " + expectedHash);

            // Prepara StreamReader para receber e gravar os chunks do arquivo:
            streamReader.beginFileTransfer(filename, fileSize);

            // Aguarda StreamReader sinalizar que todos os chunks foram recebidos e o arquivo foi fechado:
            awaitResponse(); // Consome __FILE_DONE__

            String outputPath = "received_" + filename;
            System.out.println("[Cliente] Arquivo salvo como: " + outputPath);

            // Verifica a integridade recalculando o SHA-256 do arquivo recebido:
            verifyIntegrity(outputPath, expectedHash);
        }
    }
            
    // Administra o modo chat:
    private void handleChat() throws IOException {
        System.out.println("[Chat] Modo chat ativo. Digite 'sair' para voltar ao menu.");
        System.out.println("[Chat] Comandos disponíveis: HELP, STATUS, EXIT (encerra o cliente).");

        // Loop de leitura do chat — bloqueia em scanner.nextLine() até o usuário digitar algo:
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            // Permite ao usuário sair do modo chat sem encerrar o cliente inteiro:
            if (input.equalsIgnoreCase("sair")) {
                System.out.println("[Chat] Saindo do modo chat.");
                break;
            }

            sendMsg(input);

            // Se o usuário digitou EXIT, a thread principal deve encerrar o cliente inteiro:
            if (input.equalsIgnoreCase(Protocol.CMD_EXIT)) {
                throw new IOException("__EXIT__");
            }
        }
    }

    // Calcula o SHA-256 do arquivo salvo e compara com o hash enviado pelo servidor:
    private void verifyIntegrity(String filepath, String expectedHash) throws IOException {
        String actualHash = calculateSHA256(new File(filepath));

        if (actualHash.equals(expectedHash)) {
            System.out.println("[Cliente] Integridade verificada — SHA-256 OK: " + actualHash);
        } else {
            System.out.println("[Cliente] ERRO DE INTEGRIDADE — SHA-256 não confere!");
            System.out.println("[Cliente] Esperado: " + expectedHash);
            System.out.println("[Cliente] Recebido: " + actualHash);
        }
    }

    // Calcula o hash SHA-256 do arquivo lendo-o em blocos de CHUNK_SIZE bytes:
    private String calculateSHA256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(Protocol.HASH_ALGORITHM);
            byte[] buffer = new byte[Protocol.CHUNK_SIZE];
            int bytesRead;

            // Alimenta o digest incrementalmente, bloco a bloco, sem carregar o arquivo na RAM:
            try (FileInputStream fis = new FileInputStream(file)) {
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            // Converte o array de bytes do digest para uma string hexadecimal legível:
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Algoritmo " + Protocol.HASH_ALGORITHM + " não disponível", e);
        }
    }

    // Aguarda um sinal específico de StreamReader depositar na responseQueue:
    private String awaitResponse() throws IOException {
        try {
            String signal = responseQueue.take();
            if ("__CONNECTION_CLOSED__".equals(signal)) {
                throw new IOException("Conexão encerrada pelo servidor enquanto aguardava resposta.");
            }
            return signal;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Espera interrompida.", e);
        }
    }

    // Encerra a conexão e limpa os recursos:
    private void close(StreamReader streamReader) {
        streamReader.stop();
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao fechar socket: " + e.getMessage());
        }
        scanner.close();
        System.out.println("[Cliente] Encerrado.");
    }

    // Inicia o cliente, a thread leitora e gerencia o menu principal:
    public void start() {
        System.out.println("[Cliente] Conectado a " + serverHost + ":" + serverPort);

        // Instancia e inicia a thread leitora:
        StreamReader streamReader = new StreamReader(in, responseQueue);
        Thread readerThread = new Thread(streamReader, "stream-reader");
        readerThread.setDaemon(true); // Encerra junto com a JVM caso o loop principal saia
        readerThread.start();

        while (true) {
            String choice = showMenu();
            try {
                switch (choice) {
                    case "1":
                        handleDownload(streamReader);
                        break;
                    case "2":
                        handleChat();
                        break;
                    case "3":
                        // Avisa o servidor que o cliente está saindo:
                        try { 
                            sendMsg(Protocol.CMD_EXIT); 
                        } catch (IOException ignored) {}
                        close(streamReader);
                        return;
                    default:
                        System.out.println("[Cliente] Opção inválida. Digite 1, 2 ou 3.");
                        break;
                }

            } catch (IOException e) {
                if ("__EXIT__".equals(e.getMessage())) {
                    // EXIT digitado no chat — encerra normalmente:
                    close(streamReader);
                    return;
                }
                System.err.println("[Cliente] Erro de I/O: " + e.getMessage());
                close(streamReader);
                return;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Client client = new Client();
        client.start();
    }
}