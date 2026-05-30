package Protocol;

/*
 * Define as constantes compartilhadas entre servidor e cliente.
 * Centraliza o protocolo de comunicação: strings de comando e resposta,
 * tipos de frame e parâmetros de transferência como tamanho de chunk e algoritmo de hash.
*/

public class Protocol {

    // Porta fixa onde o servidor aguarda conexões:
    public static final int PORT = 9876;

    // Tamanho dos chunks usados para ler e enviar arquivos em pedaços (8 KB):
    public static final int CHUNK_SIZE = 8 * 1024;

    // Algoritmo de hash utilizado para verificação de integridade:
    public static final String HASH_ALGORITHM = "SHA-256";

    // Diretório raiz de onde o servidor busca arquivos:
    public static final String FILES_DIR = "./arquivos";

    // Tipos de frame:
    public static final byte FRAME_MSG = 0x01;       // Mensagem de texto
    public static final byte FRAME_FILE_DATA = 0x02; // Chunk de dados binários de um arquivo em transferência
    public static final byte FRAME_FILE_END = 0x03;  // Sentinela indicando o fim de um arquivo sendo enviado

    // Comandos reconhecidos pelo servidor:
    public static final String CMD_GET = "GET";       // Solicita um arquivo: "GET <nome>"
    public static final String CMD_EXIT = "EXIT";     // Encerra a conexão com o servidor
    public static final String CMD_STATUS = "STATUS"; // Solicita o status atual do servidor
    public static final String CMD_HELP = "HELP";     // Solicita o texto de ajuda

    // Prefixos de mensagens do servidor:
    public static final String PREFIX_ADMIN = "[ADMIN NOTICE] ";
    public static final String PREFIX_EVENT = "[EVENT] ";
    public static final String PREFIX_STATUS = "[SERVER STATUS] ";
    public static final String PREFIX_ERROR = "[ERRO] ";
    public static final String PREFIX_FILE = "[ARQUIVO] ";
}