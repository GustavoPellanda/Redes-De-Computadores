package Protocol;

/*
 * Define as constantes e utilitários compartilhados entre servidor e cliente.
 * Centraliza o protocolo de comunicação: comandos de texto trocados pelo DataOutputStream/DataInputStream
 * e parâmetros de transferência como tamanho de chunk e algoritmo de hash.
 */

public class Protocol {

    // Porta fixa onde o servidor aguarda conexões:
    public static final int PORT = 9876;

    // Tamanho dos chunks usados para ler e enviar arquivos em pedaços (8 KB):
    public static final int CHUNK_SIZE = 8 * 1024;

    // Algoritmo de hash utilizado para verificação de integridade:
    public static final String HASH_ALGORITHM = "SHA-256";

    // Diretório raiz de onde o servidor serve arquivos — apenas arquivos dentro dele são permitidos:
    public static final String FILES_DIR = "./arquivos";

    // Comandos do protocolo — enviados como strings pelo cliente via DataOutputStream:
    public static final String CMD_GET  = "GET";   // Solicita um arquivo ao servidor
    public static final String CMD_EXIT = "EXIT";  // Encerra a conexão com o servidor

    // Respostas do protocolo — enviadas como strings pelo servidor via DataOutputStream:
    public static final String RESP_OK    = "OK";    // Arquivo encontrado; em seguida vêm hash + bytes
    public static final String RESP_ERROR = "ERROR"; // Arquivo não encontrado ou caminho inválido

    // Tipos de frame — primeiro byte de cada transmissão no stream TCP.
    // Permite que o leitor saiba como interpretar os bytes que seguem:
    public static final byte FRAME_CMD       = 0x01; // Comando de controle (GET, EXIT, CHAT, STATUS…)
    public static final byte FRAME_FILE_DATA = 0x02; // Dados de transferência de arquivo
    public static final byte FRAME_CHAT_MSG  = 0x03; // Mensagem de chat do servidor para o cliente
    public static final byte FRAME_FILE_END = 0x04;  // Sentinela indicando o fim de um arquivo sendo enviado

    // Comandos de chat — enviados pelo cliente como payload de um FRAME_CMD:
    public static final String CMD_CHAT   = "CHAT";   // Seguido do texto da mensagem
    public static final String CMD_STATUS = "STATUS"; // Solicita o status atual do servidor
    public static final String CMD_HELP   = "HELP";   // Solicita o texto de ajuda

    // Prefixos de mensagens de chat — incluídos no payload do FRAME_CHAT_MSG:
    public static final String PREFIX_ADMIN = "[ADMIN NOTICE] ";
    public static final String PREFIX_EVENT = "[EVENT] ";
    public static final String PREFIX_STATUS = "[SERVER STATUS] ";

}
