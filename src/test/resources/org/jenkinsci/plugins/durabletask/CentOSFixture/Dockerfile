FROM centos:7.4.1708
RUN yum -y install \
        openssh-server-7.4p1-13.el7_4 \
        java-1.8.0-openjdk-headless-1.8.0.151-1.b12.el7_4 \
    && yum clean all
RUN ssh-keygen -A
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    chown -R test:test /home/test && \
    echo "test:test" | chpasswd
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
