<template>
    <div>
        <img :src="url"/>
        <h2>{{caption}}</h2>
    </div>
</template>

<script>
export default {
    name: 'Meme',

    props: {
        url: String,
        caption: String
    }
}
</script>

<style>

</style>
