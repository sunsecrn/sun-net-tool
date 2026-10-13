#!/bin/bash
# Run locally in Terminal. Passwords are read by keytool, never by this script.
set -euo pipefail
umask 077
signing_dir="$HOME/.local/share/sun-net-tool/signing"
keystore="$signing_dir/release.jks"
alias_name="sun-net-tool-release"
if [[ -e "$keystore" ]]; then
  printf 'Já existe uma chave em %s. Nenhuma chave foi sobrescrita.\n' "$keystore"
  exit 1
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  keytool_bin="$JAVA_HOME/bin/keytool"
elif [[ -x /tmp/nettool-toolchain/jdk-17.0.20.1+1/Contents/Home/bin/keytool ]]; then
  keytool_bin=/tmp/nettool-toolchain/jdk-17.0.20.1+1/Contents/Home/bin/keytool
elif command -v keytool >/dev/null 2>&1; then
  keytool_bin=$(command -v keytool)
else
  printf 'Configure JAVA_HOME para um JDK 17 antes de continuar.\n'
  exit 1
fi
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
# Prevent two simultaneous invocations from generating over the same path.
mkdir "$signing_dir/.creation-lock" || { printf 'Outra criação está em andamento.\n'; exit 1; }
trap 'rmdir "$signing_dir/.creation-lock"' EXIT
[[ ! -e "$keystore" ]]
printf '\nSun NetTool: criação LOCAL da chave permanente\n'
printf 'Arquivo: %s\nAlias: %s\n' "$keystore" "$alias_name"
printf 'A chave RSA de 4096 bits será válida por aproximadamente 30 anos.\n'
printf 'Digite uma senha forte diretamente no Terminal; ela não será exibida.\n'
printf 'Guarde a senha no seu gerenciador de senhas. Na senha da chave, Enter reutiliza a do keystore.\n'
printf 'Nenhum segredo será enviado ao GitHub por este script.\n\n'
"$keytool_bin" -genkeypair -keystore "$keystore" -storetype JKS \
  -alias "$alias_name" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10950 -dname 'CN=Sun NetTool, OU=Android, O=Sunsec'
chmod 600 "$keystore"
printf '\nA chave foi criada. Digite novamente a senha para exportar apenas o certificado PUBLICO.\n'
"$keytool_bin" -exportcert -keystore "$keystore" -alias "$alias_name" \
  -file "$signing_dir/signing-certificate.der"
/usr/bin/shasum -a 256 "$signing_dir/signing-certificate.der" | /usr/bin/awk '{print $1}' > "$signing_dir/certificate-sha256.txt"
printf '\nCriação concluída. A chave privada permanece apenas neste Mac.\n'
printf 'Certificado público SHA-256: '
cat "$signing_dir/certificate-sha256.txt"
printf '\nFaça backup seguro de release.jks e guarde a senha separadamente.\n'
printf 'Cadastre os secrets pessoalmente seguindo docs/SIGNING-SETUP.md.\n'
printf 'Pressione Enter para encerrar.\n'
read -r
