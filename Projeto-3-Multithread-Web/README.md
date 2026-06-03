# Servidor HTTP TCP Multithreaded

## Visão Geral

Servidor HTTP simplificado implementado sobre sockets TCP em Java puro, sem bibliotecas de alto nível. Suporta o método GET, responde com os códigos de status 200 OK, 403 Forbidden, 404 Not Found e 405 Method Not Allowed, e encerra a conexão após cada resposta via `Connection: close`.

O servidor serve arquivos estáticos — HTML, imagens, CSS, JavaScript — a partir de um diretório raiz configurável (`./www`). Arquivos binários são transmitidos como bytes brutos, sem conversão de charset, garantindo que imagens e outros dados binários cheguem íntegros ao browser.

---

## Fluxo de uma requisição

```
Browser
   │
   │  TCP connect → porta 8080
   ▼
Server.accept()
   │
   │  Cria ClientHandler + Thread
   ▼
ClientHandler.run()
   │
   ├── Lê requisição HTTP do socket
   ├── Parseia linha de requisição: GET /arquivo.html HTTP/1.1
   ├── Valida método (apenas GET)
   ├── Resolve caminho → proteção contra path traversal
   │
   ├── [arquivo não encontrado] → HttpResponse.sendError(404, ...)
   ├── [path traversal]        → HttpResponse.sendError(403, ...)
   └── [arquivo encontrado]    → HttpResponse.sendFile(file, contentType)
                                       │
                                       ├── MimeTypes.getContentType(filename)
                                       ├── Monta cabeçalhos HTTP
                                       └── Envia arquivo em chunks de 8 KB
   │
   └── Fecha socket TCP
```

---

## Estrutura de Classes

### `Server.java`

Ponto de entrada da aplicação. Abre um `ServerSocket` na porta 8080 e executa um loop contínuo de aceitação de conexões. A cada `accept()`, instancia um `ClientHandler`, registra-o na lista global de conexões ativas e inicia sua thread dedicada. O loop principal retorna imediatamente ao `accept()` sem aguardar o término do handler.

A lista de handlers usa `CopyOnWriteArrayList`: inserções e remoções feitas por threads distintas não interferem em iterações concorrentes, eliminando `ConcurrentModificationException` sem necessidade de locks explícitos. O campo `running` é declarado `volatile` para garantir visibilidade imediata da flag de encerramento entre threads.

---

### `ClientHandler.java`

Thread dedicada a uma única conexão TCP. Encapsula toda a lógica de leitura da requisição e de roteamento — é o único ponto do servidor que conhece tanto o socket quanto o sistema de arquivos.

**Responsabilidades:**

- Gerenciar o ciclo de vida da conexão TCP: abertura, processamento e fechamento.
- Ler a requisição HTTP linha a linha via `BufferedReader` até a linha em branco que encerra os cabeçalhos.
- Parsear a linha de requisição e extrair método e caminho.
- Validar o método HTTP — apenas GET é suportado.
- Resolver o caminho URL para um arquivo no sistema de arquivos, aplicando proteção contra path traversal.
- Delegar a construção e envio da resposta ao `HttpResponse`.
- Remover-se da lista global ao encerrar.

**Proteção contra path traversal:**

```java
File rootDir       = new File(FILES_DIR).getCanonicalFile();
File requestedFile = new File(rootDir, urlPath).getCanonicalFile();

if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
    // Acesso negado
}
```

`getCanonicalFile()` resolve componentes `..`, symlinks e caminhos relativos para sua forma absoluta real no sistema de arquivos. Se o caminho resultante não começa com o diretório raiz, a requisição é recusada com 403 antes de qualquer leitura de arquivo.

---

### `HttpResponse.java`

Classe utilitária responsável pela construção e envio de respostas HTTP. Não conhece o conteúdo da requisição — recebe apenas o que precisa enviar e sabe como enviá-lo corretamente.

**Responsabilidades:**

- Montar o bloco de cabeçalhos HTTP com terminadores CRLF (`\r\n`), obrigatórios pelo RFC 7230.
- Calcular `Content-Length` sobre o número de bytes do corpo — não sobre o número de caracteres — para garantir valor exato com strings contendo caracteres multibyte (acentos, símbolos).
- Enviar respostas 200 OK com o conteúdo de um arquivo, lido em chunks de 8 KB diretamente no `OutputStream` do socket sem conversão de charset, preservando bytes binários intactos.
- Enviar respostas de erro com página HTML gerada em memória.

**Por que bytes brutos para o corpo do arquivo:**

```java
// CORRETO — bytes brutos, sem conversão:
out.write(buffer, 0, bytesRead);

// ERRADO para binários — conversão de charset corromperia a imagem:
out.write(new String(buffer).getBytes());
```

Passar dados binários por qualquer camada que interprete bytes como texto pode alterar sequências de bytes que não correspondem a caracteres UTF-8 válidos. Escrever diretamente no `OutputStream` garante que os bytes chegam ao browser idênticos ao arquivo original.

---

### `MimeTypes.java`

Classe utilitária estática que mapeia extensões de arquivo para seus `Content-Type` HTTP correspondentes. Mantém esse mapeamento centralizado para que nenhuma outra classe precise conhecê-lo.

**Responsabilidade única:** dado o nome de um arquivo, retornar o valor correto do cabeçalho `Content-Type`.

Retorna `application/octet-stream` para extensões desconhecidas, instruindo o browser a fazer download do arquivo em vez de tentar renderizá-lo.

---

## Subconjunto HTTP implementado

O servidor adota um subconjunto simplificado do HTTP/1.1, suficiente para os requisitos do projeto e compatível com browsers modernos.

| Aspecto | Comportamento |
|---|---|
| Método suportado | GET |
| Versão do protocolo | HTTP/1.1 (sintaxe de resposta) |
| Cabeçalhos enviados | `Content-Type`, `Content-Length`, `Connection: close` |
| Códigos de status | 200, 403, 404, 405 |
| Conexões persistentes | Não implementado — `Connection: close` após cada resposta |
| Chunked transfer encoding | Não implementado — `Content-Length` exato obrigatório |
| Requisição à raiz `/` | Retorna `index.html` |

O cabeçalho `Content-Length` é obrigatório nesta implementação. Sem ele, o browser não sabe quantos bytes aguardar e mantém a conexão em estado de carregamento até timeout.
