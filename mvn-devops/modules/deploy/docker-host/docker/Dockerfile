# The simulated deploy machine: SSH, the Docker CLI with compose, and curl.
FROM docker:cli
RUN apk add --no-cache openssh-server curl \
 && ssh-keygen -A \
 && sed -i 's/^#*PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
EXPOSE 22
ENTRYPOINT []
CMD ["/bin/sh", "-c", "mkdir -p /root/.ssh && printf '%s\\n' \"$AUTHORIZED_KEY\" > /root/.ssh/authorized_keys && chmod 700 /root/.ssh && chmod 600 /root/.ssh/authorized_keys && exec /usr/sbin/sshd -D -e"]
