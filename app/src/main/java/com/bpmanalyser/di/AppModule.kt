package com.bpmanalyzer.di

import android.content.Context
import com.bpmanalyzer.engine.audio.AudioScanner
import com.bpmanalyzer.engine.bpm.BpmDetector
import com.bpmanalyzer.engine.export.PdfExporter
import com.bpmanalyzer.engine.rename.RenameEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioScanner(): AudioScanner = AudioScanner()

    @Provides
    @Singleton
    fun provideBpmDetector(): BpmDetector = BpmDetector()

    @Provides
    @Singleton
    fun providePdfExporter(): PdfExporter = PdfExporter()

    @Provides
    @Singleton
    fun provideRenameEngine(): RenameEngine = RenameEngine()
}
