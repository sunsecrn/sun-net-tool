# Criar e cadastrar a assinatura de release

## Como funciona

A chave privada assina o APK e determina a identidade da distribuição. O Android verifica a assinatura antes de instalar ou atualizar. Para atualizações convencionais, preserve a mesma chave e aumente o `versionCode`; teste também a atualização em aparelho autorizado. O arquivo `release.jks` contém a chave privada protegida por senha. O certificado e seu SHA-256 são públicos e permitem reconhecer a assinatura sem revelar a chave.

O pipeline compila sem segredos. Depois de sua aprovação no environment `release-signing`, outro runner recebe os secrets, assina o APK, compara o certificado com o fingerprint configurado e remove o keystore temporário. O environment `release-publication` exige uma segunda aprovação antes de criar o rascunho.

## 1. Criação local

Execute em um Terminal próprio:

```sh
open scripts/create-release-key.command
```

O script solicita a senha diretamente pelo `keytool`; ela não aparece na tela e não é lida pelo script. Escolha uma senha forte, guarde-a em seu gerenciador de senhas e confirme. Quando for solicitada a senha da chave, Enter reutiliza a senha do keystore; uma senha separada também é aceita. Uma nova solicitação de senha exporta somente o certificado público.

Configuração: RSA 4096 bits, validade de 10.950 dias, alias `sun-net-tool-release`. Arquivos locais:

```text
~/.local/share/sun-net-tool/signing/release.jks
~/.local/share/sun-net-tool/signing/signing-certificate.der
~/.local/share/sun-net-tool/signing/certificate-sha256.txt
```

A pasta tem permissões 700 e a chave 600. O script recusa sobrescrever uma chave existente. Não coloque esses arquivos no repositório. Guarde um backup de `release.jks` em armazenamento seguro, e a senha separadamente. Sem a chave ou sua senha, você pode perder a capacidade de atualizar a distribuição atual. A criação local não envia dados ao GitHub e não salva automaticamente senhas no Chaves do macOS.

## 2. Cadastrar os secrets pessoalmente

Os environments já foram criados: somente `main`, aprovação manual por `fguisso`. É permitida a aprovação de uma execução iniciada pela mesma conta, pois não há outro aprovador configurado. A permissão padrão de Actions é somente leitura; apenas o job de rascunho solicita escrita.

Você deve executar os comandos abaixo pessoalmente no seu Terminal. Não envie senhas ou a chave ao agente, chat, issues, logs ou arquivos versionados. `gh secret set` criptografa o valor localmente antes de enviá-lo ao GitHub; o agente verificará apenas nomes, nunca valores.

O comando abaixo transmite a chave diretamente do arquivo para o GitHub CLI, sem imprimi-la nem copiá-la ao clipboard. Base64 não é criptografia: a proteção de transporte/armazenamento é feita pelo CLI e pelo GitHub.

```sh
base64 -i "$HOME/.local/share/sun-net-tool/signing/release.jks" | gh secret set ANDROID_KEYSTORE_BASE64 --repo sunsecrn/sun-net-tool --env release-signing
```

Execute um comando por vez; o CLI abrirá o prompt para a senha, sem incluí-la no histórico do comando:

```sh
gh secret set ANDROID_KEYSTORE_PASSWORD --repo sunsecrn/sun-net-tool --env release-signing
```

```sh
gh secret set ANDROID_KEY_PASSWORD --repo sunsecrn/sun-net-tool --env release-signing
```

Se você reutilizou a senha do keystore para a chave, informe a mesma senha nos dois secrets.

Cadastre o alias (identificador público, não é a chave privada):

```sh
printf '%s' 'sun-net-tool-release' | gh secret set ANDROID_KEY_ALIAS --repo sunsecrn/sun-net-tool --env release-signing
```

Cadastre o fingerprint público como **variável**, não como secret:

```sh
gh variable set ANDROID_SIGNING_CERT_SHA256 --repo sunsecrn/sun-net-tool --env release-signing < "$HOME/.local/share/sun-net-tool/signing/certificate-sha256.txt"
```

A alternativa pela interface fica em [Settings > Environments](https://github.com/sunsecrn/sun-net-tool/settings/environments), no environment `release-signing`. Prefira o CLI para a chave em base64, evitando imprimir ou copiar material privado. Não use `--body` com senhas literais no comando.

Depois do cadastro, o agente pode confirmar a presença dos quatro nomes de secrets e verificar a variável pública. Secrets do GitHub não substituem um backup recuperável do keystore.

## 3. Próxima verificação

Avise quando a criação e o cadastro tiverem terminado. A próxima etapa será conferir os arquivos públicos/metadata locais, os nomes dos secrets e executar o fluxo de release de uma tag revisada. Aprovações dos environments são feitas por você no GitHub; a Release permanece em rascunho até publicação explícita.

Referências: [assinatura Android](https://developer.android.com/studio/publish/app-signing), [GitHub CLI secrets](https://cli.github.com/manual/gh_secret_set), [procedimento de release](RELEASING.md).
