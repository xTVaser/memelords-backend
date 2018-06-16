<template>
    <div>
        <Meme v-for="meme in memes" :key="meme.id" :url="meme.url" :caption="meme.caption"/>
        <button @click="prevPage">Previous Page</button>
        <button @click="nextPage">Next Page</button>
    </div>
</template>

<script>
import axios from 'axios'
import Meme from './Meme.vue'

export default {
    name: 'MemeViewer',
    components: {
        Meme
    },
    data: function() {
        return {
            memes: [],
            errors: [],
            pageNumber: 0,
            pageSize: 10
        }
    },
    created() {
        axios.get('192.168.2.45:5000/memes')
        .then(response => {
            this.memes = response.data
        })
        .catch(e => {
            this.errors.push(e)
        })
    },
    methods: {
        prevPage: function(event) {
            alert('previous page')
        },
        nextPage: function(event) {
            alert('next page')
        }  
    }
}
</script>

<style>

</style>
