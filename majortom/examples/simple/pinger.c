#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <unistd.h>

int main(int argc, char** argv) {
  if (argc != 3) {
    printf("wrong argument number\n");
    return -1;
  }
  struct addrinfo mehints, *meres;
  struct addrinfo themhints, *themres;
  int sockfd;
  char buf[5];
  int pings_sent = 0;
  int pongs_received = 0;

  memset(&mehints, 0, sizeof mehints);
  mehints.ai_family = AF_INET;  // use IPv4 or IPv6, whichever
  mehints.ai_socktype = SOCK_DGRAM;
  mehints.ai_flags = AI_PASSIVE;     // fill in my IP for me

  getaddrinfo(NULL, argv[1], &mehints, &meres);



  memset(&themhints, 0, sizeof themhints);
  themhints.ai_family = AF_INET;
  themhints.ai_socktype = SOCK_DGRAM;
  themhints.ai_flags = AI_PASSIVE;

  getaddrinfo(NULL, argv[2], &themhints, &themres);
  
  // make a socket:
  sockfd = socket(meres->ai_family, meres->ai_socktype, meres->ai_protocol);

  // bind it to the port we passed in to getaddrinfo():
  bind(sockfd, meres->ai_addr, meres->ai_addrlen);

  struct sockaddr from;
  socklen_t fromsize = sizeof(from);

  sleep(5);
  while(1) {
    printf("sending ping\n");
    sendto(sockfd, "ping", 5, 0, themres->ai_addr, themres->ai_addrlen);
    pings_sent++;
    printf("receiving pong\n");
    recvfrom(sockfd, buf, 5, 0, &from, &fromsize);
    pongs_received++;
  }
}