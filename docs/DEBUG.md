# Registro debug opcional

Disponível a partir da versão 0.2.0, na tela Sessão > Registro debug. O registro começa desativado. O switch é uma opção persistida localmente; desativá-lo interrompe novos eventos sem apagar o histórico. Limpar registros apaga apenas o diário debug, preservando sessões e capturas.

## Uso

1. Abra Sessão e ative Registro debug antes de reproduzir o problema.
2. Use o aplicativo normalmente. Troque de rede ou execute uma operação apenas quando desejar testar esse comportamento.
3. Volte a Sessão e toque Exportar debug JSON. Escolha onde salvar com o seletor Android. Compartilhe esse arquivo e, se necessário, Exportar JSON da sessão.
4. Desative Registro debug quando terminar. O arquivo exportado é uma cópia; limpar o diário não remove cópias que você salvou.

## O que registra

- Ativação e desativação do registro, versão, modelo e API Android.
- Abertura/parada da atividade, telas visitadas, menus e detalhes expandidos.
- Trocas e alterações do contexto Wi-Fi: interface, handle da Network, IPs/prefixos, gateways, DNS, validação e sinalização de portal. Não registra SSID, BSSID nem MAC.
- Início, fim, duração e cancelamento de operações, ou rejeição por falta de Wi-Fi/operação ativa.
- Tipo de evidência, alvo sanitizado, relação com operação e sessão, estados e tempos disponíveis. URLs são reduzidas ao hostname; caminho, userinfo, query e fragmento não entram no alvo debug.
- Solicitações de captura, resultado de permissões, estado da captura, contadores, resultado do probe controlado e ações de exportação.
- Tipo de exceção em falhas instrumentadas. Não salva mensagem bruta de exceção no diário.

O registro cobre eventos instrumentados. Não é vídeo, gravação de todos os toques ou captura de cada interação do sistema. Eventos anteriores à ativação não são recuperados. O resultado do próprio salvamento do debug ocorre depois de gerar a cópia, portanto só aparece numa exportação posterior.

## Privacidade e limites

Não registra digitação, valores completos dos campos, corpos HTTP, headers, banners, senhas, tokens encontrados ou payloads PCAP. As chaves aceitas passam por lista explícita e mascaramento. Mesmo assim, o diário contém endereços internos, nomes de domínio e modelo do aparelho; revise antes de compartilhar.

Até 2.000 eventos e aproximadamente 1 MiB no armazenamento privado, com margem para metadados do JSON. Eventos antigos são descartados com contador visível. A fila é limitada a256 comandos; sobrecarga é contabilizada. Eventos com mais de sete dias são removidos ao abrir o processo, registrar novos eventos ou exportar. Não há rotina de expiração enquanto o aplicativo está encerrado.

Arquivo interno: files/debug/journal.json, inacessível a aplicativos comuns. Preferência: debug-consent. Exportação somente após ação explícita pelo seletor Android. Backup e transferência automática do aplicativo estão excluídos. Não há upload automático.

## Validação

Fixtures verificam exclusão de campos de entrada/body/headers, remoção de credenciais/caminho/query em URL, limite de eventos, limite de bytes UTF-8, retenção e limpeza. Teste físico no A17 depende de o aparelho ser conectado e autorizar o ADB. O S23 permanece com a versão que levou; não foi atualizado nesta etapa.


## Leitura local desde 0.4.0

Mais > Debug da sessão apresenta os registros da investigação sem exigir exportação. Há busca e filtros Todos, Erros, Portas, DNS, Rede e App. Grupos negativos de portas preservam a causa e pares porta:tempo em milissegundos. O filtro App usa somente os eventos instrumentados da sessão selecionada e requer o diário opcional ativado. A tela mostra os descartes já existentes; registros antigos eliminados por versões anteriores não podem ser recuperados.
