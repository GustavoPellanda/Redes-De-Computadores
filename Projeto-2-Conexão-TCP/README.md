# Servidor TCP de Transferência de Arquivos com Sistema de Chat

## Sumário

1. [Visão Geral do Projeto](#1-visão-geral-do-projeto)
2. [Estrutura de Pacotes e Classes](#2-estrutura-de-pacotes-e-classes)
   - 2.1 [Pacote `Protocol`](#21-pacote-protocol)
   - 2.2 [Pacote `Server`](#22-pacote-server)
3. [Modelo de Concorrência](#3-modelo-de-concorrência)
   - 3.1 [Thread por cliente](#31-thread-por-cliente)
   - 3.2 [Thread de broadcast periódico](#32-thread-de-broadcast-periódico)
4. [Gerenciamento da Lista de Clientes Ativos](#4-gerenciamento-da-lista-de-clientes-ativos)
   - 4.1 [Escolha da estrutura: `CopyOnWriteArrayList`](#41-escolha-da-estrutura-copyonwritearraylist)
   - 4.2 [Operações sobre a lista e ausência de condições de corrida](#42-operações-sobre-a-lista-e-ausência-de-condições-de-corrida)
5. [Buffers de Entrada e Saída no `ClientHandler`](#5-buffers-de-entrada-e-saída-no-clienthandler)
6. [O Protocolo de Framing sobre TCP](#6-o-protocolo-de-framing-sobre-tcp)
   - 6.1 [Por que TCP sozinho não basta](#61-por-que-tcp-sozinho-não-basta)
   - 6.2 [Estrutura do frame](#62-estrutura-do-frame)
   - 6.3 [Tipos de frame definidos](#63-tipos-de-frame-definidos)
   - 6.4 [A classe `Frame` como camada de serialização](#64-a-classe-frame-como-camada-de-serialização)
7. [Sistema de Mensagens e a Classe `ServerChat`](#7-sistema-de-mensagens-e-a-classe-serverchat)
   - 7.1 [Design stateless](#71-design-stateless)
   - 7.2 [Classificação das mensagens](#72-classificação-das-mensagens)
   - 7.3 [Fluxo de uma mensagem do servidor ao cliente](#73-fluxo-de-uma-mensagem-do-servidor-ao-cliente)
8. [Protocolo de Comunicação Completo](#8-protocolo-de-comunicação-completo)
   - 8.1 [Comandos recebidos pelo servidor](#81-comandos-recebidos-pelo-servidor)
   - 8.2 [Respostas e mensagens enviadas pelo servidor](#82-respostas-e-mensagens-enviadas-pelo-servidor)
9. [Segurança: Prevenção de Path Traversal](#9-segurança-prevenção-de-path-traversal)
10. [Verificação de Integridade via SHA-256](#10-verificação-de-integridade-via-sha-256)

---

## 1. Visão Geral do Projeto

Este projeto implementa um servidor TCP em Java capaz de atender múltiplos clientes simultaneamente, oferecendo dois serviços integrados num único canal de comunicação: transferência de arquivos sob demanda e um sistema de mensagens bidirecional.

O servidor opera em escuta contínua numa porta fixa, delegando cada conexão aceita a uma thread dedicada. Um módulo de chat independente permite ao servidor enviar mensagens assíncronas — notificações de eventos, alertas periódicos e respostas a comandos específicos — para todos os clientes conectados ou para clientes individuais.

A integração desses dois serviços num mesmo stream TCP exigiu a criação de um protocolo de aplicação próprio, baseado em *frames* tipados, capaz de diferenciar dados de arquivo de mensagens de texto dentro do fluxo contínuo de bytes que o TCP fornece.

---

## 2. Estrutura de Pacotes e Classes

O projeto está organizado em dois pacotes com responsabilidades distintas:

```
projeto/
├── Protocol/
│   ├── Protocol.java   — Constantes e definições do protocolo
│   └── Frame.java      — Serialização e desserialização de frames
└── Server/
    ├── Server.java         — Ponto de entrada, loop de aceitação e agendamento
    ├── ClientHandler.java  — Thread dedicada a cada cliente
    └── ServerChat.java     — Lógica de geração e envio de mensagens
```

### 2.1 Pacote `Protocol`

**`Protocol.java`**

Centraliza todas as constantes compartilhadas entre servidor e cliente. Funciona como o contrato formal do protocolo de comunicação: qualquer valor que precise ser reconhecido pelos dois lados — porta de conexão, tamanho de chunk, nomes de comandos, prefixos de mensagens, tipos de frame — está definido aqui. Isso garante que uma alteração num valor se propague automaticamente para todos os pontos que o utilizam, sem risco de inconsistência entre as partes.

**`Frame.java`**

Implementa a camada de framing do protocolo. Oferece métodos estáticos para escrever e ler frames no stream TCP, abstraindo os detalhes de serialização: cálculo de tamanho de payload, escolha de codificação de caracteres (UTF-8), escrita atômica de tipo + tamanho + conteúdo. Nenhuma outra classe precisa conhecer o formato binário de um frame; toda essa lógica reside aqui.

### 2.2 Pacote `Server`

**`Server.java`**

É o ponto de entrada da aplicação. Responsabilidades:

- Abrir o `ServerSocket` e mantê-lo em escuta na porta definida em `Protocol.PORT`.
- Executar o loop principal de aceitação, que bloqueia em `serverSocket.accept()` até um novo cliente se conectar.
- Para cada conexão aceita, instanciar um `ClientHandler`, registrá-lo na lista global e iniciar sua thread.
- Instanciar o `ServerChat` e mantê-lo como referência compartilhada.
- Iniciar a thread de broadcast periódico.

**`ClientHandler.java`**

Implementa `Runnable`, sendo projetada para execução em thread própria. Cada instância representa a sessão de um único cliente e é inteiramente responsável por aquele canal de comunicação. Responsabilidades:

- Inicializar os streams de entrada (`DataInputStream`) e saída (`DataOutputStream`) sobre o socket.
- Executar o loop de leitura de frames, identificando o tipo de cada frame recebido e despachando para o método de tratamento adequado.
- Processar comandos de transferência de arquivo (`GET`), incluindo validação de segurança, cálculo de hash e envio em chunks.
- Delegar comandos de chat (`HELP`, `STATUS`) ao `ServerChat`.
- Ao encerrar a sessão, remover-se da lista global e notificar os demais clientes via `ServerChat`.

**`ServerChat.java`**

Encapsula toda a lógica relacionada a mensagens de chat. Não gerencia conexões nem mantém estado de sessão — opera exclusivamente sobre referências de streams e sobre a lista de clientes ativos recebida do servidor. Responsabilidades:

- Construir e enviar mensagens de resposta a comandos específicos (`HELP`, `STATUS`).
- Realizar broadcasts para todos os clientes conectados.
- Gerar as mensagens periódicas e os eventos de conexão/desconexão.
- Calcular e formatar o tempo de atividade do servidor para o comando `STATUS`.

---

## 3. Modelo de Concorrência

O servidor adota o modelo *thread-per-connection*: cada cliente conectado é atendido por uma thread Java dedicada, criada no momento da aceitação da conexão e encerrada quando a sessão termina.

### 3.1 Thread por cliente

```
Thread principal (Server)
│
├── serverSocket.accept()  ← bloqueia aqui
│
└── Nova conexão aceita
    ├── new ClientHandler(socket, activeClients, serverChat)
    ├── activeClients.add(handler)
    └── new Thread(handler).start()  → Thread do cliente C1
                                           │
                                           └── loop: readFrame → dispatch → ...
```

O loop de `accept()` retorna imediatamente ao estado de espera após delegar o cliente, permitindo aceitar a próxima conexão sem qualquer atraso. As threads dos clientes operam em paralelo e de forma totalmente independente entre si: o bloqueio de uma thread esperando dados do seu cliente não afeta as demais.

Todas as threads de clientes são marcadas como *daemon* (`thread.setDaemon(true)`). Threads daemon são encerradas automaticamente pela JVM quando não há mais threads não-daemon em execução, evitando que conexões abertas impeçam o servidor de terminar.

### 3.2 Thread de broadcast periódico

Uma segunda thread daemon é iniciada junto ao servidor para disparar notificações periódicas. Ela dorme por um intervalo fixo (60 segundos) e, ao acordar, invoca o método de broadcast adequado no `ServerChat`. Por ser daemon, ela também encerra junto com a JVM sem necessidade de gerenciamento explícito.

```java
Thread periodicBroadcast = new Thread(() -> {
    int tickCount = 0;
    while (running) {
        Thread.sleep(60_000);
        if (tickCount % 2 == 0) serverChat.broadcastPeriodicAlive();
        else                    serverChat.broadcastPeriodicSecurity();
        tickCount++;
    }
});
periodicBroadcast.setDaemon(true);
periodicBroadcast.start();
```

---

## 4. Gerenciamento da Lista de Clientes Ativos

### 4.1 Escolha da estrutura: `CopyOnWriteArrayList`

A lista de clientes ativos é declarada como `CopyOnWriteArrayList<ClientHandler>`:

```java
private final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();
```

Essa estrutura da biblioteca padrão do Java foi projetada especificamente para cenários onde iterações são frequentes e modificações (inserções e remoções) são menos frequentes — exatamente o perfil deste servidor, onde broadcasts iteram a lista constantemente enquanto conexões e desconexões são operações pontuais.

O mecanismo interno da `CopyOnWriteArrayList` é o seguinte: toda operação de escrita (add, remove) cria uma **cópia completa do array interno**, realiza a modificação sobre essa cópia e a substitui atomicamente pela referência original. Iterações, por sua vez, operam sobre um *snapshot* imutável capturado no momento em que o iterador foi criado.

### 4.2 Operações sobre a lista e ausência de condições de corrida

Três pontos do código alteram ou percorrem a lista simultaneamente:

| Operação | Onde ocorre | Thread executora |
|---|---|---|
| `activeClients.add(handler)` | `Server.start()` | Thread principal |
| `activeClients.remove(this)` | `ClientHandler.close()` | Thread do cliente que encerrou |
| Iteração em `broadcast()` | `ServerChat.broadcast()` | Thread do cliente ou thread periódica |

Sem sincronização, uma thread removendo um cliente enquanto outra itera a lista causaria `ConcurrentModificationException` com um `ArrayList` comum. Com `CopyOnWriteArrayList`, isso não ocorre: a iteração opera sobre o snapshot capturado no início do loop, e qualquer modificação na lista durante a iteração afeta apenas o array subjacente, sem interferir no snapshot em uso.

Uma consequência intencional desse comportamento: se um cliente desconecta exatamente durante um broadcast em andamento, ele ainda pode receber aquela mensagem específica (pois estava no snapshot), mas não receberá as seguintes. Isso é semanticamente correto — a mensagem foi enviada enquanto o cliente ainda estava oficialmente ativo.

---

## 5. Buffers de Entrada e Saída no `ClientHandler`

Os streams do socket são envolvidos com `DataInputStream` e `DataOutputStream`:

```java
in  = new DataInputStream(socket.getInputStream());
out = new DataOutputStream(socket.getOutputStream());
```

Essas classes adicionam capacidade de leitura e escrita de tipos primitivos Java (`byte`, `int`, `long`) diretamente sobre o stream de bytes do socket, sem alocações intermediárias desnecessárias.

Para a transferência de arquivos, o `ClientHandler` lê e transmite o conteúdo em *chunks* de tamanho fixo definido por `Protocol.CHUNK_SIZE` (8 KB):

```java
byte[] buffer = new byte[Protocol.CHUNK_SIZE];
int bytesRead;
while ((bytesRead = fis.read(buffer)) != -1) {
    Frame.writeBinaryFrame(out, Protocol.FRAME_FILE_DATA, buffer, 0, bytesRead);
}
```

Esse padrão tem duas consequências importantes para o gerenciamento de memória:

**Limite de uso de RAM independente do tamanho do arquivo.** Independentemente de o arquivo ter 1 KB ou 10 GB, o servidor aloca apenas 8 KB por cliente para a transferência. Sem esse padrão, seria necessário carregar o arquivo inteiro em memória antes de transmiti-lo, tornando o servidor inviável para arquivos grandes.

**Limite de memória proporcional ao número de clientes, não ao tamanho dos arquivos.** Com *N* clientes transferindo arquivos simultaneamente, o consumo de memória de buffers é *N* × 8 KB — um valor previsível e controlado.

O mesmo padrão é aplicado no cálculo do hash SHA-256: o `MessageDigest` é alimentado bloco a bloco com os mesmos 8 KB, calculando o hash incremental sem precisar do arquivo completo na RAM.

---

## 6. O Protocolo de Framing sobre TCP

### 6.1 Por que TCP sozinho não basta

O TCP é um protocolo de **fluxo de bytes** (*byte stream*). Ele garante entrega ordenada e sem perdas, mas não preserva fronteiras entre transmissões. Quando o servidor executa:

```java
out.writeUTF("OK");
out.writeUTF("a3f2...hash");
out.writeLong(2048);
```

...esses três envios podem ser entregues ao cliente como um único segmento TCP, dois segmentos separados em qualquer ponto de quebra, ou até fragmentados diferentemente dependendo do estado da rede. O cliente não consegue inferir onde termina `"OK"` e começa o hash apenas observando os bytes.

Esse problema se aprofunda quando dois tipos de dados qualitativamente diferentes precisam coexistir no mesmo stream: respostas a comandos de arquivo e mensagens de chat assíncronas. Sem um mecanismo que identifique o tipo de dado antes de seu conteúdo, o cliente não sabe se os próximos bytes são a confirmação de um download ou uma notificação de evento.

### 6.2 Estrutura do frame

A solução adotada é um esquema de *framing* simples com três campos:

```
┌────────────┬──────────────────────────┬────────────────────────────────┐
│  Tipo      │  Tamanho do payload      │  Payload                       │
│  (1 byte)  │  (4 bytes, big-endian)   │  (N bytes, UTF-8 ou binário)   │
└────────────┴──────────────────────────┴────────────────────────────────┘
```

**Tipo (1 byte):** identifica como o payload deve ser interpretado. O leitor lê este byte primeiro e decide qual método de desserialização aplicar ao restante.

**Tamanho (4 bytes, inteiro com sinal em big-endian):** indica quantos bytes compõem o payload. Com 4 bytes, o limite teórico é ~2 GB por frame, suficiente para qualquer caso de uso prático.

**Payload (N bytes):** o conteúdo real. Para frames de texto, os bytes são uma string codificada em UTF-8. Para frames de dados de arquivo, são bytes brutos do arquivo.

Esse esquema é uma instância do padrão *length-prefix framing* (ou *TLV — Type-Length-Value*), amplamente utilizado em protocolos de aplicação como HTTP/2, Protocol Buffers e TLS.

### 6.3 Tipos de frame definidos

| Constante | Valor | Uso |
|---|---|---|
| `FRAME_CMD` | `0x01` | Comandos de controle e respostas textuais (`GET`, `EXIT`, `OK`, `ERROR`) |
| `FRAME_FILE_DATA` | `0x02` | Chunk de dados binários de um arquivo em transferência |
| `FRAME_CHAT_MSG` | `0x03` | Mensagem de chat enviada pelo servidor ao cliente |
| `FRAME_FILE_END` | `0x04` | Frame vazio sinalizando o fim de uma transferência de arquivo |

### 6.4 A classe `Frame` como camada de serialização

A classe `Frame` isola completamente o restante do código dos detalhes do protocolo binário. Os métodos `writeTextFrame`, `writeBinaryFrame` e `writeEmptyFrame` garantem que tipo, tamanho e payload sejam sempre escritos na ordem correta e de forma atômica do ponto de vista do chamador. Os métodos `readFrameType`, `readTextPayload` e `skipPayload` fazem o caminho inverso.

O método `readFully` é usado intencionalmente na leitura do payload:

```java
in.readFully(payload); // Garante leitura completa, mesmo em rede lenta
```

A alternativa, `in.read(payload)`, pode retornar com menos bytes do que o solicitado caso o sistema operacional ainda não tenha recebido o restante do segmento TCP — comportamento correto do ponto de vista da API, mas que produziria um payload truncado. `readFully` bloqueia até que todos os `length` bytes estejam disponíveis, garantindo a integridade do frame.

---

## 7. Sistema de Mensagens e a Classe `ServerChat`

### 7.1 Design stateless

`ServerChat` é projetada como uma classe *stateless* em relação às sessões individuais dos clientes. Ela não armazena filas de mensagens por cliente, não mantém estado de leitura nem rastreia quais mensagens já foram entregues a quem. O único estado interno que ela mantém é:

- `activeClients`: uma referência à lista global de handlers (não é estado próprio — é uma referência compartilhada com o servidor).
- `startTimeMillis`: o instante de criação, usado exclusivamente para calcular o uptime no comando `STATUS`.

Isso tem uma consequência direta: **uma mensagem broadcast é enviada exatamente uma vez, para todos os clientes presentes no momento do envio**. Clientes que conectam após um broadcast não recebem mensagens retroativas, e clientes que desconectam durante um broadcast simplesmente falham silenciosamente na escrita (a exceção é capturada em `sendMessage`). Não há reenvio, persistência ou confirmação de entrega.

Essa escolha é adequada para mensagens de notificação — eventos de conexão, alertas periódicos — onde a entrega melhor-esforço é semanticamente correta.

### 7.2 Classificação das mensagens

As mensagens do sistema se dividem em quatro categorias funcionais:

**Respostas a comandos do cliente (unicast, sob demanda)**

São mensagens enviadas para um único cliente, em resposta direta a um comando recebido. O cliente é o iniciador.

| Comando recebido | Resposta enviada |
|---|---|
| `HELP` | Texto de ajuda descrevendo todos os comandos disponíveis |
| `STATUS` | Número de clientes conectados e tempo de atividade formatado |
| `GET <arquivo>` | `OK` + hash SHA-256 + tamanho + chunks do arquivo, ou `ERROR` + descrição |

**Broadcasts engatilhados por eventos (multicast, assíncrono)**

São enviados automaticamente quando um evento relevante ocorre no servidor, independentemente de qualquer requisição de cliente.

| Evento | Mensagem enviada |
|---|---|
| Novo cliente conectado | `[EVENT] Novo cliente conectado ao servidor.` |
| Cliente desconectado | `[EVENT] Cliente desconectado.` |

**Broadcasts periódicos (multicast, agendado)**

Enviados em intervalos regulares pela thread de broadcast, sem gatilho externo. Alternam entre duas mensagens a cada ciclo:

- `[ADMIN NOTICE] Servidor está ativo e funcionando normalmente.`
- `[ADMIN NOTICE] Todas as conexões estão criptograficamente verificadas via SHA-256.`

**Respostas de protocolo de arquivo (unicast, parte do handshake de transferência)**

São mensagens de controle trocadas como parte do fluxo de transferência de arquivo, distinguidas dos demais tipos pelo uso do frame `FRAME_CMD` em vez de `FRAME_CHAT_MSG`.

### 7.3 Fluxo de uma mensagem do servidor ao cliente

Toda mensagem de chat percorre o seguinte caminho antes de chegar ao cliente:

```
ServerChat.broadcast(texto)
    └── para cada handler em activeClients:
            ServerChat.sendMessage(handler.getOutputStream(), texto)
                └── Frame.writeTextFrame(out, FRAME_CHAT_MSG, texto)
                        ├── out.writeByte(0x03)
                        ├── out.writeInt(texto.length em bytes UTF-8)
                        └── out.write(bytes UTF-8 do texto)
```

O cliente, ao ler do stream, encontra o byte `0x03` como tipo de frame, sabe que o payload é uma mensagem de chat, lê o tamanho e então o texto — sem qualquer ambiguidade com dados de arquivo ou respostas a comandos.

---

## 8. Protocolo de Comunicação Completo

### 8.1 Comandos recebidos pelo servidor

Todos os comandos do cliente são enviados como payload de um frame `FRAME_CMD` (`0x01`).

| Comando | Frame(s) enviado(s) pelo cliente | Descrição |
|---|---|---|
| `GET` | `FRAME_CMD("GET")` seguido de `FRAME_CMD("<nome_do_arquivo>")` | Solicita o download de um arquivo |
| `EXIT` | `FRAME_CMD("EXIT")` | Encerra a sessão graciosamente |
| `HELP` | `FRAME_CMD("HELP")` | Solicita o texto de ajuda |
| `STATUS` | `FRAME_CMD("STATUS")` | Solicita métricas do servidor |

### 8.2 Respostas e mensagens enviadas pelo servidor

**Em resposta a `GET` (sucesso):**
```
FRAME_CMD("OK")
FRAME_CMD("<hash SHA-256 em hexadecimal>")
writeLong(<tamanho total em bytes>)
FRAME_FILE_DATA(<chunk 0>)
FRAME_FILE_DATA(<chunk 1>)
...
FRAME_FILE_END
```

**Em resposta a `GET` (falha):**
```
FRAME_CMD("ERROR")
FRAME_CMD("<mensagem de erro descritiva>")
```

**Em resposta a `HELP` ou `STATUS`:**
```
FRAME_CMD("OK")       ← confirmação de comando reconhecido (apenas HELP)
FRAME_CHAT_MSG("<texto da resposta>")
```

**Mensagens assíncronas (broadcast):**
```
FRAME_CHAT_MSG("<prefixo> <conteúdo>")
```

---

## 9. Segurança: Prevenção de Path Traversal

O servidor valida todo caminho de arquivo solicitado antes de abri-lo. O ataque de *path traversal* consiste em enviar nomes de arquivo contendo sequências como `../../etc/passwd` para navegar fora do diretório autorizado.

A defesa implementada resolve ambos os caminhos para suas formas canônicas e verifica a relação de prefixo:

```java
File rootDir       = new File(Protocol.FILES_DIR).getCanonicalFile();
File requestedFile = new File(rootDir, filename).getCanonicalFile();

if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
    // Acesso negado
}
```

`getCanonicalFile()` resolve symlinks, remove componentes `.` e `..` e retorna o caminho absoluto real no sistema de arquivos. Se o caminho resultante não começa com o diretório raiz, o acesso é negado antes que qualquer operação de I/O seja realizada.

---

## 10. Verificação de Integridade via SHA-256

Antes de transmitir um arquivo, o servidor calcula seu hash SHA-256 completo e o envia ao cliente como parte do cabeçalho da transferência. O cliente pode, após receber todos os bytes, calcular o hash do arquivo recebido e compará-lo com o valor recebido.

O cálculo é feito de forma incremental, alimentando o `MessageDigest` bloco a bloco com os mesmos 8 KB usados na transferência:

```java
MessageDigest digest = MessageDigest.getInstance("SHA-256");
while ((bytesRead = fis.read(buffer)) != -1) {
    digest.update(buffer, 0, bytesRead);
}
byte[] hashBytes = digest.digest();
```

Isso garante que o consumo de memória durante o cálculo do hash seja idêntico ao da transferência: constante e limitado a 8 KB, independentemente do tamanho do arquivo.

A verificação via SHA-256 detecta tanto corrupção acidental de dados em trânsito quanto modificações maliciosas no arquivo entre o cálculo do hash e o recebimento pelo cliente — o que o prefixo `[ADMIN NOTICE] Todas as conexões estão criptograficamente verificadas via SHA-256` anuncia periodicamente aos clientes conectados.
