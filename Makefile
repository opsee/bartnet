DB_HOST := "postgres"
BARTNET_HOST ?= "localhost"

build: deps
	@docker run --link bartnet_postgres_1:postgres -e DB_HOST=postgres -e LEIN_USERNAME=$LEIN_USERNAME -e LEIN_PASSPHRASE=$LEIN_PASSPHRASE -v ~/.m2:/root/.m2 -v `pwd`:/build quay.io/opsee/build-clj

deps:
	@docker info
	@docker-compose version
	@docker-compose stop
	@docker-compose rm -f
	@docker-compose up -d
	@lein deps

sql:
	@docker run --link bartnet_postgres_1:postgres -it sameersbn/postgresql:9.4-3 psql -U postgres -h postgres bartnet_test

docker: build
	@lein docker

run: docker
	docker run --link bartnet_nsqd_1:nsqd --link bartnet_nsqlookupd_1:nsqlookdup --link bartnet_postgres_1:postgres -d -e DB_HOST=postgres -e NSQ_LOOKUP_HOST=nsqlookupd -e NSQ_LOOKUP_PORT=4161 -e NSQ_PRODUCE_HOST=nsqd -e NSQ_PRODUCE_PORT=4150 -p 8080:8080 quay.io/opsee/bartnet

live-test: run
	@sleep 20
	./test_liveness.sh

.PHONY: docker live-test deps build
