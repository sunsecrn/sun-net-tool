# Laboratório local sintético

Serviços para testes autorizados do Sun NetTool, sem dados ou credenciais reais. Python 3 e OpenSSL são suficientes. O processo escuta em `0.0.0.0`, portanto fica acessível pelas interfaces IPv4 do computador enquanto estiver ativo. Encerrar após os testes.

## Operação

```sh
python3 tests/lab/lab.py start
python3 tests/lab/lab.py status
python3 tests/lab/verify.py
python3 tests/lab/lab.py stop
```

Estado, PID, certificado de teste e log ficam em `/private/tmp/nettool-lab`. A variável `NETTOOL_LAB_STATE` permite outro diretório. O certificado é gerado localmente, válido por sete dias, autoassinado, com nome `nettool-lab.invalid`. Não instalar como CA no aparelho. O app deve relatar erro de confiança ou hostname ao acessar HTTPS. O verificador confia explicitamente nesse certificado somente em um teste local para confirmar que o servidor TLS responde.

## Serviços esperados

| Porta TCP | Serviço | Resultado esperado |
| --- | --- | --- |
| 80 | HTTP | Resposta instrumentada com `X-NetTool-Fixture: synthetic-only` |
| 8443 | HTTPS | Handshake disponível, certificado rejeitado pelo cliente padrão |
| 2222 | Banner | `SSH-2.0-NetToolSynthetic_1.0`, sem login ou shell |

Use o IP IPv4 atual do computador na rede compartilhada com o aparelho. Não deduzir alcance apenas por ambos estarem no Wi-Fi: isolamento de clientes e firewall podem bloquear. Não há DNS ou varredura fornecidos pelo laboratório.

| Caminho HTTP | Fixture |
| --- | --- |
| `/`, `/landing` | HTML com referências ao IP real aceito pelo socket do servidor nas portas 80/8443, `portal.example.invalid`, `api.example.invalid`, IP de documentação `192.0.2.10` e padrões de segredo inteiramente falsos |
| `/fixture.js` | JavaScript sintético com token falso e URL HTTP no IP real do servidor |
| `/health` | JSON fixo de saúde |
| `/redirect` | Dois redirects HTTP relativos até `/landing` |
| `/loop-a` | Loop de redirects entre `/loop-a` e `/loop-b` |
| `/auth` | HTTP 401 e desafio Basic, sem fornecer credenciais |
| `/forbidden` | HTTP 403 |
| `/cookie` | Cookie de sessão falso, esperado mascaramento no relatório |
| `/large` | Corpo de 460000 bytes, para verificar limite de leitura |
| `/slow` | Resposta após três segundos |

Não seguir os domínios `.invalid` ou o IP de documentação como alvos reais. Eles servem apenas para verificar extração e proveniência. O servidor não recebe uploads, não fornece autenticação e não executa comandos. Seus logs omitem URL, cabeçalhos e endereço do cliente.

Os seis testes em `tests/lab/verify.py` fazem conexões somente a `127.0.0.1`. Validam HTML, cadeia de redirects, loop/desafios/cookie/tamanho, banner e comportamento TLS. A validação no Android deve ser registrada separadamente, com serial explícito do aparelho autorizado.


## Casos de portal e cabeçalhos

`/generate_204` retorna 302 para `/portal-login`, cuja página é sintética e não aceita credenciais. `/online/generate_204` retorna 204. `/headers` devolve User-Agent e Accept em cabeçalhos de resposta para verificar o perfil configurado. Esses casos validam interpretação de respostas, sem simular isolamento de clientes ou controle real de acesso à internet.
