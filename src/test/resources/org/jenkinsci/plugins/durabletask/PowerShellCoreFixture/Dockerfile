FROM mcr.microsoft.com/powershell:ubuntu-bionic
RUN apt-get update -y && \
    apt-get install -y \
        openssh-server \
        openjdk-8-jre-headless \
        locales
# openssh installs procps, and thus /bin/ps, as a dependency, so to reproduce JENKINS-52881 we need to delete it:
RUN dpkg --force-depends -r procps
RUN mkdir -p /var/run/sshd
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    chown -R test:test /home/test && \
    echo "test:test" | chpasswd
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
