<template>
  <div id="app">
    <h1>Memelords.io</h1>
    <MemeCreator/>
    <MemeViewer></MemeViewer>
  </div>
</template>

<script>
import MemeCreator from './components/MemeCreator.vue'
import MemeViewer from './components/MemeViewer.vue'

export default {
  name: 'app',
  components: {
    MemeCreator,
    MemeViewer
  }
}
</script>

<style>
#app {
  font-family: 'Avenir', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
  margin-top: 60px;
}
</style>
