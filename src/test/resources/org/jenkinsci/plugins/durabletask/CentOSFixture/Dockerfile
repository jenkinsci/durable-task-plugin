FROM centos:7.9.2009
RUN yum -y install \
        openssh-server \
        java-11-openjdk-headless \
    && yum clean all
RUN ssh-keygen -A
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    chown -R test:test /home/test && \
    echo "test:test" | chpasswd
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
