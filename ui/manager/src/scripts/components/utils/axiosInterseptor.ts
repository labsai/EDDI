
import axios from 'axios'

const axiosApiInstance = axios.create();

axiosApiInstance.interceptors.request.use(
  async (config) => {
    const bearer = localStorage.getItem("bearer");
    if (bearer) {
        config.headers = {
          Authorization: `Basic ${localStorage.getItem("bearer")}`,
        };
    }
    return config;
  },
  (error) => {
    Promise.reject(error);
  }
);

export default axiosApiInstance;