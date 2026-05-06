task :default => [:info, :build]

MOD_ID   = "ZBExhume41"
MOD_TYPE = ""
VERSIONS = {
  "42.13" => "24",
}

VERSIONS.each do |ver, jdk_ver|
  desc "build for #{ver}"
  task "build:#{ver}" do
    Dir.chdir("java") do
      env = {
        "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-#{jdk_ver}.jdk/Contents/Home"
      }
      sh env, "gradle build -PZVersion=#{ver}"
    end
    dst_dir = "#{ver}/media/java/#{MOD_TYPE}"
    FileUtils.mkdir_p dst_dir
    FileUtils.mv "java/build/libs/#{MOD_ID}-#{ver}.jar", "#{dst_dir}/#{MOD_ID}.jar"
    FileUtils.mv "java/build/libs/#{MOD_ID}-#{ver}.jar.zbs", "#{dst_dir}/#{MOD_ID}.jar.zbs"
  end
end

desc "build all"
task :build => VERSIONS.keys.map { |ver| "build:#{ver}" }

desc "update steam.txt from info.yml"
task :info do
  require 'yaml'
  info = YAML.load_file('../ZModUnbork/info.yml')
  in_lines = File.readlines('steam.txt')

  class ModInfo
    attr_accessor :id, :title, :zb, :comment

    def initialize(id, title:, comment: nil, zb: false, exhume: false)
      if title.is_a?(Hash)
        title, comment = title.values_at("title", "comment")
      end
      @id      = id
      @comment = comment ? " (#{comment})" : nil
      @title   = title
    end
  end

  mods = [
    info['b41_mods'].map     { |id, title| ModInfo.new(id, title:) },
  ].map(&:to_a).flatten.sort_by(&:title)

  bad_mods = [
    info.fetch('b41_bad_mods', {}).map { |id, title| ModInfo.new(id, title:) },
  ].map(&:to_a).flatten.sort_by(&:title)

  out_lines = []
  i = 0
  while i < in_lines.size
    line = in_lines[i]
    out_lines << line

    if line =~ /^\[h1\]Exhumed mods/
      out_lines << "[list]\n"
      mods.each do |m|
        out_lines << "    [*][url=https://steamcommunity.com/sharedfiles/filedetails/?id=#{m.id}]#{m.title}[/url]#{m.comment}\n"
      end
      out_lines << "[/list]\n"
      out_lines << "\n"
        
      i += 1 while in_lines[i].strip != ""
    end

    if line =~ /^\[h1\]NOT working mods/
      out_lines << "[list]\n"
      bad_mods.each do |m|
        out_lines << "    [*][url=https://steamcommunity.com/sharedfiles/filedetails/?id=#{m.id}]#{m.title}[/url]#{m.comment}\n"
      end
      out_lines << "[/list]\n"
      out_lines << "\n"
        
      i += 1 while in_lines[i].strip != ""
    end

    i += 1
  end

  File.write('steam.txt', out_lines.join)
end

desc "show steam url"
task :url do
  puts "https://steamcommunity.com/sharedfiles/filedetails/?id=3718604798"
end
