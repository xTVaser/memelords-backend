<template>
  <div class="hello">
    <input v-model="meme.url" type="text" placeholder="enter url here"/>
    <input v-model="meme.caption" type="text" placeholder="caption"/>
    <button @click="submit">Submit</button>
    <hr/>
  </div>
</template>

<script>
export default {
  name: 'MemeCreator',
  data: function() {
    return {
      meme: {id: 0, url: '', caption: ''}
    }
  },
  methods: {
    submit: function() {
      this.$root.emit('meme', )
    }
  }
}
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped>
h3 {
  margin: 40px 0 0;
}
ul {
  list-style-type: none;
  padding: 0;
}
li {
  display: inline-block;
  margin: 0 10px;
}
a {
  color: #42b983;
}
</style>
