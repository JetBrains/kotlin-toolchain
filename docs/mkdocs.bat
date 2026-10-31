@rem We use the --livereload flag explicitly because of https://github.com/squidfunk/mkdocs-material/issues/8478
docker run --rm -it -p 8000:8000 -v "%cd%:/docs" -e NO_MKDOCS_2_WARNING=1 hildan/mkdocs-material-with-plugins:20261030-6d4756e serve --dev-addr=0.0.0.0:8000 --livereload %*
