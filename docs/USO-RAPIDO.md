# Sun NetTool: uso rápido

No primeiro acesso, o guia destaca os controles reais com Compose Showcase. Use Próximo e Voltar, toque no controle destacado para utilizá-lo ou Pular para sair. Os dez passos incluem perfis de portas, segurança e possíveis CVEs, cópia da sessão em partes e Configurações gerais com debug e GPS opcional. O progresso é salvo. Para repetir, abra Menu > Modo tutor. Avançar no guia não executa diagnósticos nem solicita permissões.

1. Em Rede, confira Meu aparelho, Gateway e DNS. Usar gateway preenche o alvo. Investigar DNS da rede consulta nomes dos endereços observados e mostra resultados em Alvos > DNS.
2. Em Descoberta, use a faixa preenchida ou selecione um lote /24 quando a rede for maior. Cada execução continua limitada a 256 alvos. mDNS consulta anúncios locais como complemento ao DNS comum.
3. Use Mais recon para selecionar um alvo. Testar portas usa 1.000 comuns, com nmap-services importado, ou portas personalizadas. A opção Identificar serviços faz verificações de protocolo em até 16 portas abertas. O resumo informa abertas, recusadas, sem resposta e erros.
4. Em HTTP, informe a URL e execute. O app abre Alvos > Respostas, incluindo cabeçalhos e erros. Os demais filtros em Alvos reúnem serviços, nomes, referências e indícios. Menu > Configurações gerais > Perfil e cabeçalhos HTTP permite escolher ou editar os cabeçalhos de compatibilidade.
5. Em Captura, inicie a VPN local. Use um teste pronto, o gateway ou um serviço observado, e toque Gerar tráfego. Com captura ativa, os diagnósticos de Rede e HTTP também passam pela VPN. Pare a captura antes de buscar texto/regex ou exportar PCAP. HTTPS permanece criptografado no PCAP.
6. Menu > Configurações gerais > Abrir debug da sessão mostra as tentativas e seus resultados, com busca e filtros. Abra um grupo de portas para ver cada porta, tempo e causa. Configurações gerais > Registro debug ativa também eventos de navegação, disponíveis no filtro App.
7. Verificar portal consulta um endereço público que normalmente responde 204. Respostas diferentes e redirects aparecem como indícios em Alvos > Portal. Abrir Wi-Fi / login leva às configurações Android para acessar o login oferecido pela rede. Refaça a verificação depois do login; o app não fornece autenticação própria.
8. Em Sessão, exporte JSON/CSV ou reabra uma investigação. Menu > Nova sessão inicia outra investigação. A exportação preserva os grupos com os resultados individuais das portas.

## Registro disponível

O relatório JSON contém sessão, ações de diagnóstico iniciadas, horários, alvos, resultados, erros, origem e vínculos de evidências. Alguns módulos registram tempos DNS/TCP/TLS/HTTP. Captura parada adiciona resumo e fluxos. Não é gravação de todas as interações: toques, navegação e todas as trocas de rede não são registrados sistematicamente. Na versão0.2.0, Configurações gerais > Registro debug permite ativar um diário adicional de navegação, ações, mudanças de rede, operações e erros. Use Exportar debug JSON para compartilhar e desative após testar. Não é gravação de todos os toques. Veja DEBUG.md.

Relatórios mascaram credenciais conhecidas e parâmetros de URL. PCAP contém bytes originais, inclusive dados claros possivelmente sensíveis; prefira JSON da sessão ao compartilhar análise geral. Até 20 sessões e 4.000 grupos/eventos por sessão. Captura limitada a10min/25MiB, somente NetTool, sem descriptografia HTTPS.

Roteiros particulares e relatórios de sessões não são publicados. Os ensaios de desenvolvimento não garantem cobertura de uma rede específica. O app não garante descobrir segundo range ou todos domínios internos sem evidências observáveis.


## Leitura de evidências e cobertura (0.4.1)

Os cartões indicam Ver detalhes e Ocultar detalhes. Recon prepara o alvo para investigação; Preparar HTTP abre a requisição. Em serviços, IP e porta TCP têm rótulos próprios. Um nome PTR ou título de página é apresentado como informação da fonte, não como confirmação do fabricante.

Respostas HTTP separam a requisição, a resposta recebida, os cabeçalhos do servidor e as medições do app. Dados técnicos ficam recolhidos. Ver investigação relacionada abre os registros da mesma operação e referências ligadas ao resultado. Registros antigos sem vínculo podem ter uma visão relacionada incompleta.

Descoberta rápida tenta três portas; descoberta ampliada tenta até mil portas por endereço e encerra no primeiro sinal de presença. Nenhuma das duas substitui o scan completo de cada alvo. A ampliada pode demorar muito quando os endereços não respondem. DNS consulta PTR e confirma os nomes retornados com A e AAAA, sem garantir enumeração de todos os domínios internos.


### Perfis e filtros de portas

Em Investigar um alvo, escolha 1.000 comuns, Todas TCP ou Personalizadas. A opção Todas TCP testa de 1 a 65535 em um único alvo; não inclui UDP. Personalizadas aceita portas e intervalos, por exemplo 80,443,8000-8100. Cancelar preserva as tentativas concluídas; uma execução interrompida não equivale a cobertura completa.

Em Alvos, busque IP, nome, porta ou protocolo. Em Serviços, Reconhecidos mostra protocolos observados e Tipo desconhecido mostra portas abertas ainda sem identificação. O nome da porta não é usado como prova do produto.


## Versões, possíveis CVEs e headers de segurança (0.5.0)

Execute uma requisição HTTP ou leia um banner SSH. Em Alvos > Segurança, abra um registro para ver a evidência, a resposta de origem e a interpretação. Serviços também apresentam a versão anunciada quando reconhecida.

A identificação inicial reconhece Apache HTTP Server, nginx e PHP nos headers, e OpenSSH no banner SSH. Ela usa versões declaradas, não confirma o software efetivo, correções da distribuição ou explorabilidade. A análise de headers é local: HSTS em HTTPS; CSP, proteção contra enquadramento e nosniff em HTML; atributos de até 40 cookies sem armazenar seus valores como evidência. Não é uma auditoria completa de políticas.

No cartão da versão, Consultar possíveis CVEs consulta a NVD pela internet. Envia somente o identificador de produto e versão (CPE), sem endereço do alvo, cookies ou headers coletados. A NVD recebe naturalmente a conexão de rede do aparelho. Não existe consulta automática ao coletar uma versão.

Resultados são possíveis correspondências. Cada registro mantém o vínculo com a versão observada, o identificador consultado, data, descrição, pontuação publicada quando disponível e link da NVD. O app mostra até 50 resultados por consulta e avisa quando a lista é parcial. Uma resposta vazia não significa que o serviço seja seguro; falhas da API são registradas como consulta não concluída.

Referências: https://nvd.nist.gov/developers/vulnerabilities e https://owasp.org/www-project-secure-headers/.

### Copiar para continuar a análise

Os cartões oferecem **Copiar item** ou **Copiar achado**. Nos detalhes há **Copiar evidência** e, na investigação relacionada, **Copiar investigação**. Em **Sessão**, use **Copiar sessão para análise** para levar todos os registros preservados a outro agente.

A cópia inclui contexto, IDs, vínculos, resultados e metadados no mesmo formato mascarado da exportação. Informa registros já omitidos e mantém a distinção entre versões anunciadas e vulnerabilidades confirmadas. Não inclui arquivos PCAP nem corpos brutos. Nenhum conteúdo é enviado automaticamente a agentes.

Conteúdos acima de 400.000 bytes abrem **Copiar em partes**. Copie e cole todas as partes numeradas na ordem. O conteúdo é preservado, e cada parte informa que a análise deve aguardar as demais. Para um arquivo único, use **Exportar JSON**. O app confirma a cópia ou informa a falha.


### Progresso durante os testes (0.5.1)

Uma faixa fixa acima da navegação mostra a etapa real, o alvo, o tempo decorrido e o botão Cancelar. A contagem indica portas processadas, não portas abertas. DNS, HTTP, TLS, SSH e consultas de CVEs informam o que estão aguardando. A descoberta informa IPs processados. mDNS explica a janela de escuta. O gerador de tráfego e a busca PCAP têm status e cancelamento próprios.

O resumo copiado informa a rede, scans concluídos, portas abertas únicas, tipos de registros, operações sem conclusão e a presença ou ausência de captura e consultas de CVEs. Os registros completos preservados seguem depois do resumo.


### Configurações gerais e localização (0.6.0)

O ícone de três pontos abre o menu. Configurações gerais reúne perfil HTTP, registro/debug e Anotar GPS / localização. A localização vem desligada e só solicita permissão quando ativada. O Android permite localização precisa ou aproximada.

A coleta é local e somente enquanto o app está visível. Posições recentes incluem coordenadas, horário da medição, precisão, provedor e indicação de posição simulada quando aplicável. Evidências registradas até 60 segundos depois recebem locationRecordId. A posição pertence ao telefone, não ao alvo remoto. A captura marca as amostras coletadas enquanto está ativa; o PCAP mantém seu formato original, e as posições ficam no JSON da sessão para correlação por horário. Coleta pausada em segundo plano não inventa posições para pacotes capturados depois.

Em Sessão, Ver posições desta sessão abre as medições. Cópias e exportações incluem essas coordenadas. Desligar interrompe novos registros; posições já salvas permanecem na sessão. GPS indisponível, permissão negada ou ausência de posição são apresentados como estado de espera, sem coordenadas presumidas.
